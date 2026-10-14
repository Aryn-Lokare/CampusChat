package com.campuschat.client.gui;

import com.campuschat.model.Role;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main JavaFX Application for Campus Chat System.
 * Orchestrates views, network lifecycle, theme styling, and session transitions.
 */
public class CampusChatApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(CampusChatApp.class);

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;

    private Stage primaryStage;
    private NetworkClient networkClient;
    private SessionState sessionState;

    private AuthView authView;
    private ChatView chatView;
    private AdminDashboardView adminDashboardView;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        this.sessionState = new SessionState();

        // Parse host/port arguments if passed
        Parameters params = getParameters();
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        if (!params.getRaw().isEmpty()) {
            host = params.getRaw().get(0);
        }
        if (params.getRaw().size() > 1) {
            try {
                port = Integer.parseInt(params.getRaw().get(1));
            } catch (NumberFormatException ignored) {}
        }

        // Initialize Network Client
        networkClient = new NetworkClient(host, port);
        networkClient.setMessageListener(this::dispatchServerMessage);
        networkClient.setStatusListener(status -> {
            if (chatView != null) {
                chatView.updateConnectionStatus(status);
            }
            if (adminDashboardView != null) {
                adminDashboardView.updateConnectionStatus(status);
            }
        });

        // Initialize Views
        authView = new AuthView(networkClient, this::onLoginSuccess);

        // Initial Scene
        scene = new Scene(authView, 900, 680);
        loadStylesheet(scene);

        primaryStage.setTitle("Campus Chat System - Batch Communications");
        primaryStage.setMinWidth(750);
        primaryStage.setMinHeight(550);
        primaryStage.setScene(scene);

        // Graceful cleanup on window close
        primaryStage.setOnCloseRequest(e -> {
            logger.info("Closing Campus Chat Client...");
            if (adminDashboardView != null) {
                adminDashboardView.stopPolling();
            }
            networkClient.shutdown();
            Platform.exit();
            System.exit(0);
        });

        primaryStage.show();

        // Connect to server in background
        networkClient.connect();
    }

    private void onLoginSuccess(String[] loginInfo) {
        String username = loginInfo[0];
        String sessionToken = loginInfo[1];

        sessionState.setUsername(username);
        sessionState.setSessionToken(sessionToken);

        // Request live user profile from server to decide between ChatView and AdminDashboardView
        networkClient.send("WHOAMI");
    }

    private void onLogout() {
        if (adminDashboardView != null) {
            adminDashboardView.stopPolling();
            adminDashboardView = null;
        }
        chatView = null;
        sessionState.clear();
        networkClient.setSavedSessionToken(null);
        scene.setRoot(authView);
    }

    private void dispatchServerMessage(String line) {
        if (line == null) return;

        if (line.startsWith("ME|")) {
            // Format: ME|username|role|batchId
            String[] parts = line.split("\\|", 4);
            if (parts.length >= 4) {
                sessionState.setUsername(parts[1]);
                try {
                    sessionState.setRole(Role.valueOf(parts[2].toUpperCase()));
                } catch (IllegalArgumentException e) {
                    sessionState.setRole(Role.STUDENT);
                }
                try {
                    sessionState.setBatchId(Integer.parseInt(parts[3]));
                } catch (NumberFormatException e) {
                    sessionState.setBatchId(1);
                }

                // If user is ADMIN, navigate to AdminDashboardView (FR-16)
                if (sessionState.isAdmin()) {
                    if (adminDashboardView == null) {
                        adminDashboardView = new AdminDashboardView(networkClient, sessionState, this::onLogout);
                        scene.setRoot(adminDashboardView);
                    }
                } else {
                    if (chatView == null) {
                        chatView = new ChatView(networkClient, sessionState, this::onLogout);
                        scene.setRoot(chatView);
                    } else {
                        chatView.refreshUserProfile();
                    }
                }
            }
            return;
        }

        // Forward to current active scene view
        if (scene.getRoot() == authView) {
            authView.handleServerResponse(line);
        } else if (scene.getRoot() == adminDashboardView && adminDashboardView != null) {
            adminDashboardView.handleIncomingLine(line);
        } else if (chatView != null) {
            chatView.handleIncomingLine(line);
        }
    }

    private void loadStylesheet(Scene sc) {
        try {
            String cssPath = getClass().getResource("/styles.css") != null
                    ? getClass().getResource("/styles.css").toExternalForm()
                    : null;
            if (cssPath != null) {
                sc.getStylesheets().add(cssPath);
            }
        } catch (Exception e) {
            logger.warn("Could not load external stylesheet: {}", e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
