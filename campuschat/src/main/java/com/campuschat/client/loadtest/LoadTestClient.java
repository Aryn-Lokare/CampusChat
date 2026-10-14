package com.campuschat.client.loadtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-concurrency NIO load test simulator (PRD Section 6, 14, 15).
 * Opens up to 500 simultaneous non-blocking TCP socket connections against the
 * Campus Chat Server to verify selector scalability, memory footprint, and low latency.
 */
public class LoadTestClient {

    private static final Logger logger = LoggerFactory.getLogger(LoadTestClient.class);

    private final String host;
    private final int port;
    private final int totalConnections;

    private final AtomicInteger connectedCount = new AtomicInteger(0);
    private final AtomicInteger responsesReceived = new AtomicInteger(0);
    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    public LoadTestClient(String host, int port, int totalConnections) {
        this.host = host;
        this.port = port;
        this.totalConnections = totalConnections;
    }

    public LoadTestResult run() throws IOException, InterruptedException {
        System.out.println("==================================================");
        System.out.println("   Campus Chat NIO Server - 500 Connection Test   ");
        System.out.println("==================================================");
        System.out.printf("Target: %s:%d | Total Target Connections: %d%n", host, port, totalConnections);

        long startTime = System.currentTimeMillis();
        Selector selector = Selector.open();
        List<SocketChannel> channels = new ArrayList<>(totalConnections);
        Map<SocketChannel, Long> pingSendTimes = new ConcurrentHashMap<>();

        // 1. Initiate 500 non-blocking connections
        System.out.println("\n[PHASE 1] Opening " + totalConnections + " non-blocking TCP sockets...");
        for (int i = 0; i < totalConnections; i++) {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(host, port));
            channel.register(selector, SelectionKey.OP_CONNECT, i);
            channels.add(channel);
        }

        long connectDeadline = System.currentTimeMillis() + 15000; // 15 sec timeout
        while (connectedCount.get() < totalConnections && System.currentTimeMillis() < connectDeadline) {
            int ready = selector.select(200);
            if (ready == 0) continue;

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (!key.isValid()) continue;

                if (key.isConnectable()) {
                    SocketChannel ch = (SocketChannel) key.channel();
                    try {
                        if (ch.finishConnect()) {
                            connectedCount.incrementAndGet();
                            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                        }
                    } catch (IOException e) {
                        key.cancel();
                        ch.close();
                    }
                }
            }
        }

        long connectDuration = System.currentTimeMillis() - startTime;
        System.out.printf("[CONNECTED] %d / %d sockets successfully connected in %d ms%n",
                connectedCount.get(), totalConnections, connectDuration);

        // 2. Broadcast sample messages/pings across all connected channels
        System.out.println("\n[PHASE 2] Measuring round-trip response latency across connections...");
        ByteBuffer pingBuffer = ByteBuffer.wrap("UNKNOWN_CMD|ping\n".getBytes(StandardCharsets.UTF_8));

        for (SocketChannel ch : channels) {
            if (ch.isConnected()) {
                try {
                    pingSendTimes.put(ch, System.currentTimeMillis());
                    ch.write(pingBuffer.duplicate());
                } catch (IOException ignored) {}
            }
        }

        // 3. Read responses from server
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        long readDeadline = System.currentTimeMillis() + 10000;
        while (responsesReceived.get() < connectedCount.get() && System.currentTimeMillis() < readDeadline) {
            int ready = selector.select(200);
            if (ready == 0) continue;

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (key.isValid() && key.isReadable()) {
                    SocketChannel ch = (SocketChannel) key.channel();
                    readBuffer.clear();
                    int bytesRead = ch.read(readBuffer);
                    if (bytesRead > 0) {
                        Long sendTime = pingSendTimes.remove(ch);
                        if (sendTime != null) {
                            long lat = System.currentTimeMillis() - sendTime;
                            latencies.add(lat);
                            responsesReceived.incrementAndGet();
                        }
                    } else if (bytesRead == -1) {
                        key.cancel();
                        ch.close();
                    }
                }
            }
        }

        // 4. Clean up connections
        System.out.println("\n[PHASE 3] Closing sockets cleanly...");
        for (SocketChannel ch : channels) {
            try {
                if (ch.isOpen()) ch.close();
            } catch (IOException ignored) {}
        }
        selector.close();

        // 5. Compute performance statistics
        long minLatency = 0, maxLatency = 0, avgLatency = 0, p95Latency = 0;
        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            minLatency = latencies.get(0);
            maxLatency = latencies.get(latencies.size() - 1);
            long sum = 0;
            for (long l : latencies) sum += l;
            avgLatency = sum / latencies.size();
            p95Latency = latencies.get((int) (latencies.size() * 0.95));
        }

        System.out.println("==================================================");
        System.out.println("             LOAD TEST SUMMARY RESULTS            ");
        System.out.println("==================================================");
        System.out.printf("Total Sockets Attempted: %d%n", totalConnections);
        System.out.printf("Sockets Connected:       %d (%.1f%%)%n", connectedCount.get(), (connectedCount.get() * 100.0 / totalConnections));
        System.out.printf("Responses Handled:       %d%n", responsesReceived.get());
        System.out.printf("Latency (Min / Avg / P95 / Max): %d ms / %d ms / %d ms / %d ms%n",
                minLatency, avgLatency, p95Latency, maxLatency);
        System.out.println("==================================================\n");

        return new LoadTestResult(connectedCount.get(), responsesReceived.get(), minLatency, avgLatency, p95Latency, maxLatency);
    }

    public record LoadTestResult(int connected, int responses, long minMs, long avgMs, long p95Ms, long maxMs) {}

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 500;

        try {
            LoadTestClient client = new LoadTestClient(host, port, count);
            client.run();
        } catch (Exception e) {
            System.err.println("[LOADTEST ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }
}
