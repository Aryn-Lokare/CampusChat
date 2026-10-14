package com.campuschat.server.network;

import com.campuschat.server.service.CommandRouter;
import com.campuschat.server.state.BatchRoomRegistry;
import com.campuschat.server.state.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

/**
 * High-performance, non-blocking TCP server using Java NIO.
 * Holds hundreds of simultaneous connections on a single Selector thread.
 * Network I/O and framing run exclusively on the selector thread;
 * all business logic and Hibernate calls run on the worker thread pool.
 */
public class NioServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(NioServer.class);

    private final int port;
    private final CommandRouter commandRouter;
    private final SessionRegistry sessionRegistry;
    private final BatchRoomRegistry batchRoomRegistry;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running = false;
    private Thread serverThread;

    // Worker pool for offloading commands from selector thread
    private final ExecutorService workerPool;

    // Queue of connections needing OP_WRITE registration
    private final ConcurrentLinkedQueue<ConnectionContext> pendingWrites = new ConcurrentLinkedQueue<>();

    public NioServer(int port, CommandRouter commandRouter, SessionRegistry sessionRegistry, BatchRoomRegistry batchRoomRegistry) {
        this(port, commandRouter, sessionRegistry, batchRoomRegistry,
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4,
                        new ThreadFactory() {
                            private int count = 1;
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "worker-thread-" + count++);
                                t.setDaemon(true);
                                return t;
                            }
                        }));
    }

    public NioServer(int port, CommandRouter commandRouter, SessionRegistry sessionRegistry,
                     BatchRoomRegistry batchRoomRegistry, ExecutorService workerPool) {
        this.port = port;
        this.commandRouter = commandRouter;
        this.sessionRegistry = sessionRegistry;
        this.batchRoomRegistry = batchRoomRegistry;
        this.workerPool = workerPool;
    }

    public synchronized void start() throws IOException {
        if (running) return;

        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;
        serverThread = new Thread(this, "nio-selector-thread");
        serverThread.start();
        logger.info("NioServer started on port {}", port);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;

        if (selector != null) {
            selector.wakeup();
        }

        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
        } catch (IOException e) {
            logger.warn("Error closing server channels: {}", e.getMessage());
        }

        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(3, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("NioServer stopped cleanly");
    }

    public int getPort() {
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            } catch (IOException ignored) {}
        }
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Request the selector to register OP_WRITE for a connection.
     */
    public void requestWrite(ConnectionContext ctx) {
        pendingWrites.add(ctx);
        if (selector != null) {
            selector.wakeup();
        }
    }

    @Override
    public void run() {
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(8192);

        while (running) {
            try {
                // Apply pending OP_WRITE registrations
                processPendingWrites();

                int readyChannels = selector.select();
                if (!running) break;

                if (readyChannels == 0) {
                    processPendingWrites();
                    continue;
                }

                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key, readBuffer);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    logger.error("Exception in selector loop: {}", e.getMessage(), e);
                }
            }
        }
    }

    private void processPendingWrites() {
        ConnectionContext ctx;
        while ((ctx = pendingWrites.poll()) != null) {
            SelectionKey key = ctx.getSelectionKey();
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = ssc.accept();
        if (clientChannel == null) return;

        clientChannel.configureBlocking(false);
        clientChannel.socket().setTcpNoDelay(true);

        ConnectionContext ctx = new ConnectionContext(clientChannel, this);
        SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ, ctx);
        ctx.setSelectionKey(clientKey);

        logger.debug("Accepted new connection from {}", clientChannel.getRemoteAddress());
    }

    private void handleRead(SelectionKey key, ByteBuffer readBuffer) {
        ConnectionContext ctx = (ConnectionContext) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        readBuffer.clear();
        int bytesRead;
        try {
            bytesRead = channel.read(readBuffer);
        } catch (IOException e) {
            disconnectClient(ctx, "Read error: " + e.getMessage());
            return;
        }

        if (bytesRead == -1) {
            disconnectClient(ctx, "Client disconnected (EOF)");
            return;
        }

        if (bytesRead > 0) {
            readBuffer.flip();
            try {
                List<String> lines = ctx.getLineBuffer().processBytes(readBuffer);
                for (String line : lines) {
                    // Hand off complete command lines to worker pool (never block selector thread)
                    workerPool.submit(() -> commandRouter.route(ctx, line));
                }
            } catch (LineBuffer.LineTooLongException e) {
                logger.warn("Client {} exceeded 4 KB line limit, dropping connection", channel);
                ctx.send("ERROR|Line exceeded 4 KB maximum protocol limit");
                disconnectClient(ctx, "Line too long");
            }
        }
    }

    private void handleWrite(SelectionKey key) {
        ConnectionContext ctx = (ConnectionContext) key.attachment();
        try {
            boolean fullyDrained = ctx.drain();
            if (fullyDrained) {
                // Done writing for now; clear OP_WRITE interest to avoid busy-spin
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            disconnectClient(ctx, "Write error: " + e.getMessage());
        }
    }

    private void disconnectClient(ConnectionContext ctx, String reason) {
        logger.debug("Disconnecting client ({}): {}", ctx.getUsername(), reason);
        sessionRegistry.unregister(ctx);
        batchRoomRegistry.remove(ctx);
        if (commandRouter != null && commandRouter.getRateLimiter() != null) {
            commandRouter.getRateLimiter().remove(ctx);
        }
        ctx.close();
    }
}
