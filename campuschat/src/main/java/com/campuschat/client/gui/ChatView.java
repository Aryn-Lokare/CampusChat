package com.campuschat.client.gui;

import com.campuschat.model.Role;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.Optional;

/**
 * Main Chat Room view for Batch communications.
 * Displays pinned priority announcements, event notices, real-time message bubbles,
 * faculty action modals, and dynamic connection status.
 */
public class ChatView extends BorderPane {

    private final NetworkClient networkClient;
    private final SessionState sessionState;
    private final Runnable onLogout;

    private VBox messageFeedBox;
    private ScrollPane scrollPane;
    private TextField inputField;
    private Button sendButton;

    // Header elements
    private Label batchLabel;
    private Label userBadge;
    private Label roleBadge;
    private Circle statusCircle;
    private Label statusText;

    // Pinned announcement container
    private VBox pinnedAnnouncementBox;
    private Label pinnedSenderLabel;
    private Label pinnedContentLabel;

    public ChatView(NetworkClient networkClient, SessionState sessionState, Runnable onLogout) {
        this.networkClient = networkClient;
        this.sessionState = sessionState;
        this.onLogout = onLogout;
        getStyleClass().add("app-container");

        initUI();
    }

    private void initUI() {
        // 1. Top Header Bar
        setTop(createHeaderBar());

        // 2. Center Feed (Pinned Announcement + Scrollable Messages)
        VBox centerContainer = new VBox();
        VBox.setVgrow(centerContainer, Priority.ALWAYS);

        // Pinned announcement card (hidden until an announcement arrives)
        pinnedAnnouncementBox = createPinnedAnnouncementBanner();
        pinnedAnnouncementBox.setVisible(false);
        pinnedAnnouncementBox.setManaged(false);

        // Scrollable Message Feed
        messageFeedBox = new VBox(12);
        messageFeedBox.setPadding(new Insets(16));

        scrollPane = new ScrollPane(messageFeedBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        centerContainer.getChildren().addAll(pinnedAnnouncementBox, scrollPane);
        setCenter(centerContainer);

        // 3. Bottom Input and Action Bar
        setBottom(createBottomBar());
    }

    private HBox createHeaderBar() {
        HBox header = new HBox(16);
        header.getStyleClass().add("header-bar");
        header.setAlignment(Pos.CENTER_LEFT);

        // Branding & Batch info
        VBox titleBox = new VBox(2);
        Label appTitle = new Label("Campus Chat");
        appTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #f8fafc;");

        Integer bId = sessionState.getBatchId() != null ? sessionState.getBatchId() : 1;
        batchLabel = new Label("Batch #" + bId + " Academic Room");
        batchLabel.getStyleClass().add("text-subtitle");
        titleBox.getChildren().addAll(appTitle, batchLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Connection Status Indicator
        HBox statusBox = new HBox(6);
        statusBox.setAlignment(Pos.CENTER);
        statusCircle = new Circle(4, Color.web(NetworkClient.ConnectionStatus.CONNECTED.getColorHex()));
        statusText = new Label("Connected");
        statusText.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
        statusBox.getChildren().addAll(statusCircle, statusText);

        // Current User Profile Pill
        HBox userBox = new HBox(8);
        userBox.setAlignment(Pos.CENTER);

        userBadge = new Label("👤 " + (sessionState.getUsername() != null ? sessionState.getUsername() : "User"));
        userBadge.setStyle("-fx-font-size: 13px; -fx-text-fill: #f1f5f9; -fx-font-weight: bold;");

        Role role = sessionState.getRole() != null ? sessionState.getRole() : Role.STUDENT;
        roleBadge = new Label(role.name());
        updateRoleBadgeStyle(role);

        userBox.getChildren().addAll(userBadge, roleBadge);

        // Logout Button
        Button logoutBtn = new Button("Logout");
        logoutBtn.getStyleClass().add("btn-danger");
        logoutBtn.setOnAction(e -> {
            networkClient.send("LOGOUT");
            onLogout.run();
        });

        header.getChildren().addAll(titleBox, spacer, statusBox, new Separator(javafx.geometry.Orientation.VERTICAL), userBox, logoutBtn);
        return header;
    }

    private VBox createPinnedAnnouncementBanner() {
        VBox banner = new VBox(4);
        banner.getStyleClass().add("pinned-announcement");
        banner.setPadding(new Insets(10, 16, 10, 16));

        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("📢 PINNED ANNOUNCEMENT");
        icon.setStyle("-fx-text-fill: #f59e0b; -fx-font-weight: bold; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        pinnedSenderLabel = new Label();
        pinnedSenderLabel.setStyle("-fx-text-fill: #d97706; -fx-font-size: 11px;");

        topRow.getChildren().addAll(icon, spacer, pinnedSenderLabel);

        pinnedContentLabel = new Label();
        pinnedContentLabel.setStyle("-fx-text-fill: #fef3c7; -fx-font-size: 13px; -fx-font-weight: bold;");
        pinnedContentLabel.setWrapText(true);

        banner.getChildren().addAll(topRow, pinnedContentLabel);
        return banner;
    }

    private VBox createBottomBar() {
        VBox container = new VBox(8);
        container.setPadding(new Insets(12, 16, 16, 16));
        container.getStyleClass().add("status-bar");

        // Faculty Action Buttons (shown only for Faculty/Admin)
        HBox facultyBar = new HBox(10);
        facultyBar.setAlignment(Pos.CENTER_LEFT);

        Button announceBtn = new Button("📢 Post Announcement");
        announceBtn.getStyleClass().add("btn-warning");
        announceBtn.setOnAction(e -> showAnnouncementDialog());

        Button eventBtn = new Button("📅 Post Event Notice");
        eventBtn.getStyleClass().add("btn-success");
        eventBtn.setOnAction(e -> showEventDialog());

        Label facultyNotice = new Label("Faculty Controls (High Priority Delivery)");
        facultyNotice.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");

        facultyBar.getChildren().addAll(announceBtn, eventBtn, facultyNotice);

        // Show faculty controls only if role permits
        boolean isPrivileged = sessionState.isFaculty() || sessionState.isAdmin();
        facultyBar.setVisible(isPrivileged);
        facultyBar.setManaged(isPrivileged);

        // Input row
        HBox inputRow = new HBox(10);
        inputRow.setAlignment(Pos.CENTER);

        inputField = new TextField();
        inputField.setPromptText("Type your message here... (Enter to send)");
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputField.setOnAction(e -> handleSendMessage());

        sendButton = new Button("Send ➤");
        sendButton.getStyleClass().add("btn-primary");
        sendButton.setOnAction(e -> handleSendMessage());

        inputRow.getChildren().addAll(inputField, sendButton);
        container.getChildren().addAll(facultyBar, inputRow);

        return container;
    }

    private void handleSendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        Integer bId = sessionState.getBatchId() != null ? sessionState.getBatchId() : 1;
        networkClient.send("CHAT|" + bId + "|" + text);
        inputField.clear();
    }

    private void showAnnouncementDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Post Priority Announcement");
        dialog.setHeaderText("Broadcast an urgent announcement to Batch #" + sessionState.getBatchId() + ".\nThis message is dispatched at Priority 1 ahead of regular chat.");

        ButtonType postBtnType = new ButtonType("Post Announcement", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(postBtnType, ButtonType.CANCEL);

        TextArea contentArea = new TextArea();
        contentArea.setPromptText("Enter announcement message...");
        contentArea.setWrapText(true);
        contentArea.setPrefRowCount(4);

        dialog.getDialogPane().setContent(contentArea);
        dialog.setResultConverter(btn -> btn == postBtnType ? contentArea.getText().trim() : null);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(msg -> {
            if (!msg.isEmpty()) {
                Integer bId = sessionState.getBatchId() != null ? sessionState.getBatchId() : 1;
                networkClient.send("ANNOUNCE|" + bId + "|" + msg);
            }
        });
    }

    private void showEventDialog() {
        Dialog<String[]> dialog = new Dialog<>();
        dialog.setTitle("Post Campus Event Notice");
        dialog.setHeaderText("Schedule an event notice for Batch #" + sessionState.getBatchId());

        ButtonType postBtnType = new ButtonType("Schedule Event", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(postBtnType, ButtonType.CANCEL);

        VBox form = new VBox(10);
        TextField titleField = new TextField();
        titleField.setPromptText("Event Title (e.g. Midterm Examination)");

        TextField dateField = new TextField();
        dateField.setPromptText("Date & Time (e.g. 2026-10-25 10:00 AM)");

        form.getChildren().addAll(new Label("Title:"), titleField, new Label("Date/Time:"), dateField);
        dialog.getDialogPane().setContent(form);

        dialog.setResultConverter(btn -> {
            if (btn == postBtnType && !titleField.getText().trim().isEmpty()) {
                return new String[]{titleField.getText().trim(), dateField.getText().trim()};
            }
            return null;
        });

        Optional<String[]> result = dialog.showAndWait();
        result.ifPresent(arr -> {
            Integer bId = sessionState.getBatchId() != null ? sessionState.getBatchId() : 1;
            networkClient.send("EVENT|" + bId + "|" + arr[0] + "|" + arr[1]);
        });
    }

    public void handleIncomingLine(String line) {
        if (line.startsWith("MSG|")) {
            // MSG|type|sender|batchId|content|timestamp
            String[] parts = line.split("\\|", 6);
            if (parts.length >= 5) {
                String type = parts[1].toUpperCase();
                String sender = parts[2];
                String content = parts[4];
                String timestamp = parts.length >= 6 ? parts[5] : "";

                switch (type) {
                    case "ANNOUNCE" -> addAnnouncementCard(sender, content, timestamp);
                    case "EVENT" -> addEventCard(sender, content, timestamp);
                    default -> addChatBubble(sender, content, timestamp);
                }
            }
        } else if (line.startsWith("ERROR|")) {
            addSystemNotice("⚠️ " + line.substring(6), "#f87171");
        } else if (line.startsWith("SYNC_START")) {
            addSystemNotice("🔄 Synchronizing offline messages...", "#38bdf8");
        } else if (line.startsWith("SYNC_END")) {
            addSystemNotice("✓ Sync complete. All missed messages delivered.", "#34d399");
        }
    }

    private void addChatBubble(String sender, String content, String timestamp) {
        boolean isCurrentUser = sender.equalsIgnoreCase(sessionState.getUsername());

        HBox row = new HBox();
        row.setAlignment(isCurrentUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox bubble = new VBox(3);
        bubble.setMaxWidth(480);

        if (isCurrentUser) {
            bubble.getStyleClass().add("msg-bubble-user");
        } else {
            bubble.getStyleClass().add("msg-bubble-other");
        }

        // Header inside bubble
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label senderLbl = new Label(isCurrentUser ? "You" : sender);
        senderLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: " + (isCurrentUser ? "#c7d2fe" : "#93c5fd") + ";");

        Label timeLbl = new Label(formatTime(timestamp));
        timeLbl.getStyleClass().add("text-muted");

        header.getChildren().addAll(senderLbl, timeLbl);

        Label msgText = new Label(content);
        msgText.setWrapText(true);
        msgText.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 13px;");

        bubble.getChildren().addAll(header, msgText);
        row.getChildren().add(bubble);

        appendFeed(row);
    }

    private void addAnnouncementCard(String sender, String content, String timestamp) {
        // 1. Update pinned announcement bar at the top
        pinnedSenderLabel.setText("Posted by " + sender + " • " + formatTime(timestamp));
        pinnedContentLabel.setText(content);
        pinnedAnnouncementBox.setVisible(true);
        pinnedAnnouncementBox.setManaged(true);

        // 2. Add high-contrast priority card into the chat feed
        VBox card = new VBox(6);
        card.getStyleClass().add("msg-card-announcement");
        card.setMaxWidth(Double.MAX_VALUE);

        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label badge = new Label("📢 PRIORITY ANNOUNCEMENT");
        badge.setStyle("-fx-background-color: #f59e0b; -fx-text-fill: #000000; -fx-font-weight: bold; " +
                "-fx-background-radius: 4px; -fx-padding: 2px 8px; -fx-font-size: 11px;");

        Label senderInfo = new Label("by " + sender + " (" + formatTime(timestamp) + ")");
        senderInfo.setStyle("-fx-text-fill: #fcd34d; -fx-font-size: 12px;");

        topRow.getChildren().addAll(badge, senderInfo);

        Label text = new Label(content);
        text.setWrapText(true);
        text.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 14px; -fx-font-weight: bold;");

        card.getChildren().addAll(topRow, text);
        appendFeed(card);
    }

    private void addEventCard(String sender, String content, String timestamp) {
        VBox card = new VBox(6);
        card.getStyleClass().add("msg-card-event");
        card.setMaxWidth(Double.MAX_VALUE);

        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label badge = new Label("📅 CAMPUS EVENT");
        badge.setStyle("-fx-background-color: #0284c7; -fx-text-fill: #ffffff; -fx-font-weight: bold; " +
                "-fx-background-radius: 4px; -fx-padding: 2px 8px; -fx-font-size: 11px;");

        Label senderInfo = new Label("Scheduled by " + sender + " (" + formatTime(timestamp) + ")");
        senderInfo.setStyle("-fx-text-fill: #7dd3fc; -fx-font-size: 12px;");

        topRow.getChildren().addAll(badge, senderInfo);

        Label text = new Label(content);
        text.setWrapText(true);
        text.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 13px;");

        card.getChildren().addAll(topRow, text);
        appendFeed(card);
    }

    private void addSystemNotice(String notice, String colorHex) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER);

        Label label = new Label(notice);
        label.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 12px; -fx-padding: 4px 12px; " +
                "-fx-background-color: rgba(0,0,0,0.3); -fx-background-radius: 12px;");

        row.getChildren().add(label);
        appendFeed(row);
    }

    private void appendFeed(javafx.scene.Node node) {
        messageFeedBox.getChildren().add(node);
        // Auto-scroll to bottom on JavaFX pulse
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }

    public void updateConnectionStatus(NetworkClient.ConnectionStatus status) {
        statusCircle.setFill(Color.web(status.getColorHex()));
        statusText.setText(status.getLabel());
    }

    public void refreshUserProfile() {
        userBadge.setText("👤 " + (sessionState.getUsername() != null ? sessionState.getUsername() : "User"));
        Role role = sessionState.getRole() != null ? sessionState.getRole() : Role.STUDENT;
        roleBadge.setText(role.name());
        updateRoleBadgeStyle(role);

        Integer bId = sessionState.getBatchId() != null ? sessionState.getBatchId() : 1;
        batchLabel.setText("Batch #" + bId + " Academic Room");
    }

    private void updateRoleBadgeStyle(Role role) {
        roleBadge.getStyleClass().removeAll("badge-student", "badge-faculty", "badge-admin");
        switch (role) {
            case FACULTY -> roleBadge.getStyleClass().add("badge-faculty");
            case ADMIN -> roleBadge.getStyleClass().add("badge-admin");
            default -> roleBadge.getStyleClass().add("badge-student");
        }
    }

    private String formatTime(String ts) {
        if (ts == null || ts.isBlank()) return "";
        // Clean ISO-8601 string to time only
        if (ts.contains("T")) {
            String timePart = ts.substring(ts.indexOf("T") + 1);
            if (timePart.contains(".")) {
                timePart = timePart.substring(0, timePart.indexOf("."));
            }
            return timePart;
        }
        return ts;
    }
}
