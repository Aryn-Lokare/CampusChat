package com.campuschat.client.gui;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Handles TCP socket connection to Campus Chat NIO server.
 * Runs a dedicated background daemon reader thread and safely dispatches
 * UI callbacks to the JavaFX Application Thread via Platform.runLater().
 */
public class NetworkClient {

    private static final Logger logger = LoggerFactory.getLogger(NetworkClient.class);

    public enum ConnectionStatus {
        CONNECTED("Connected", "#10b981"),
        RECONNECTING("Reconnecting...", "#f59e0b"),
        SYNCING("Syncing History...", "#38bdf8"),
        DISCONNECTED("Disconnected", "#ef4444");

        private final String label;
        private final String colorHex;

        ConnectionStatus(String label, String colorHex) {
            this.label = label;
            this.colorHex = colorHex;
        }

        public String getLabel() {
            return label;
        }

        public String getColorHex() {
            return colorHex;
        }
    }

    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean manualDisconnect = new AtomicBoolean(false);
    private final ExecutorService senderPool = Executors.newSingleThreadExecutor();

    private Consumer<ConnectionStatus> statusListener;
    private Consumer<String> messageListener;

    private volatile String savedSessionToken;

    public NetworkClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setStatusListener(Consumer<ConnectionStatus> statusListener) {
        this.statusListener = statusListener;
    }

    public void setMessageListener(Consumer<String> messageListener) {
        this.messageListener = messageListener;
    }

    public synchronized void connect() {
        manualDisconnect.set(false);
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            isRunning.set(true);

            updateStatus(ConnectionStatus.CONNECTED);

            readerThread = new Thread(this::readLoop, "CampusChat-ReaderThread");
            readerThread.setDaemon(true);
            readerThread.start();
            logger.info("Connected to server at {}:{}", host, port);
        } catch (Exception e) {
            logger.error("Connection failed: {}", e.getMessage());
            updateStatus(ConnectionStatus.DISCONNECTED);
        }
    }

    private void readLoop() {
        try {
            String line;
            while (isRunning.get() && (line = in.readLine()) != null) {
                final String msg = line;
                logger.debug("Received: {}", msg);

                if (msg.startsWith("SYNC_START")) {
                    updateStatus(ConnectionStatus.SYNCING);
                } else if (msg.startsWith("SYNC_END")) {
                    updateStatus(ConnectionStatus.CONNECTED);
                }

                if (messageListener != null) {
                    Platform.runLater(() -> messageListener.accept(msg));
                }
            }
        } catch (Exception e) {
            if (isRunning.get()) {
                logger.warn("Server connection interrupted: {}", e.getMessage());
            }
        } finally {
            closeSocket();
            if (!manualDisconnect.get()) {
                updateStatus(ConnectionStatus.DISCONNECTED);
                // Attempt auto-reconnect if session token exists
                if (savedSessionToken != null) {
                    triggerAutoReconnect();
                }
            }
        }
    }

    private void triggerAutoReconnect() {
        new Thread(() -> {
            try {
                updateStatus(ConnectionStatus.RECONNECTING);
                Thread.sleep(2500); // Backoff before retry
                if (!manualDisconnect.get() && savedSessionToken != null) {
                    connect();
                    if (isConnected()) {
                        send("RECONNECT|" + savedSessionToken);
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "CampusChat-AutoReconnect").start();
    }

    public void send(String rawCommand) {
        if (rawCommand == null) return;
        senderPool.submit(() -> {
            try {
                if (out != null && socket != null && socket.isConnected() && !socket.isClosed()) {
                    out.println(rawCommand);
                    logger.debug("Sent: {}", rawCommand);
                }
            } catch (Exception e) {
                logger.error("Failed to send command: {}", e.getMessage());
            }
        });
    }

    private void updateStatus(ConnectionStatus status) {
        if (statusListener != null) {
            Platform.runLater(() -> statusListener.accept(status));
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void setSavedSessionToken(String token) {
        this.savedSessionToken = token;
    }

    public String getSavedSessionToken() {
        return savedSessionToken;
    }

    public synchronized void disconnect() {
        manualDisconnect.set(true);
        isRunning.set(false);
        closeSocket();
        updateStatus(ConnectionStatus.DISCONNECTED);
    }

    private synchronized void closeSocket() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception ignored) {
        }
    }

    public void shutdown() {
        disconnect();
        senderPool.shutdownNow();
    }
}
