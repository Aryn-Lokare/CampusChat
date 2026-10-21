package com.campuschat.client.gui;

import com.campuschat.model.Role;
import com.campuschat.model.UserStatus;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Admin Dashboard View (FR-11, FR-16).
 * Live system telemetry polling every 5s, user moderation (ban/unban/role),
 * and batch management.
 */
public class AdminDashboardView extends BorderPane {

    private final NetworkClient networkClient;
    private final SessionState sessionState;
    private final Runnable onLogout;

    // Telemetry metric cards
    private Label onlineUsersVal;
    private Label activeSessionsVal;
    private Label pendingQueueVal;
    private Label messagesTodayVal;

    // Status bar
    private Circle statusCircle;
    private Label statusText;
    private Label feedbackLabel;

    // User Table
    private TableView<UserRow> userTable;
    private final ObservableList<UserRow> userList = FXCollections.observableArrayList();

    // Batch Table
    private TableView<BatchRow> batchTable;
    private final ObservableList<BatchRow> batchList = FXCollections.observableArrayList();

    // Batch creation inputs
    private TextField batchNameField;
    private TextField batchYearField;

    private Timeline pollingTimeline;

    public AdminDashboardView(NetworkClient networkClient, SessionState sessionState, Runnable onLogout) {
        this.networkClient = networkClient;
        this.sessionState = sessionState;
        this.onLogout = onLogout;
        getStyleClass().add("app-container");

        initUI();
        startPolling();
    }

    private void initUI() {
        setTop(createHeader());

        VBox centerBox = new VBox(16);
        centerBox.setPadding(new Insets(20));

        // Feedback / Notification banner
        feedbackLabel = new Label();
        feedbackLabel.setVisible(false);
        feedbackLabel.setManaged(false);
        feedbackLabel.setMaxWidth(Double.MAX_VALUE);
        feedbackLabel.setPadding(new Insets(8, 12, 8, 12));

        // 1. Telemetry Cards Row
        HBox metricsRow = createMetricsRow();

        // 2. TabPane for Users and Batches
        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("panel-card");
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        Tab usersTab = new Tab("User Accounts & Moderation", createUsersPane());
        usersTab.setClosable(false);

        Tab batchesTab = new Tab("Academic Batches", createBatchesPane());
        batchesTab.setClosable(false);

        tabPane.getTabs().addAll(usersTab, batchesTab);

        centerBox.getChildren().addAll(feedbackLabel, metricsRow, tabPane);
        setCenter(centerBox);
    }

    private HBox createHeader() {
        HBox header = new HBox(16);
        header.getStyleClass().add("header-bar");
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("👑 Campus Chat Admin Portal");
        title.getStyleClass().add("text-title");
        title.setStyle("-fx-font-size: 18px; -fx-text-fill: #a855f7;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Connection status
        HBox statusBox = new HBox(6);
        statusBox.setAlignment(Pos.CENTER);
        statusCircle = new Circle(4, Color.web(NetworkClient.ConnectionStatus.CONNECTED.getColorHex()));
        statusText = new Label("Connected");
        statusText.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
        statusBox.getChildren().addAll(statusCircle, statusText);

        // Admin profile pill
        Label adminPill = new Label("Admin: " + (sessionState.getUsername() != null ? sessionState.getUsername() : "SuperUser"));
        adminPill.getStyleClass().add("badge-admin");

        Button refreshBtn = new Button("🔄 Refresh");
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refreshData());

        Button logoutBtn = new Button("Logout");
        logoutBtn.getStyleClass().add("btn-danger");
        logoutBtn.setOnAction(e -> {
            stopPolling();
            networkClient.send("LOGOUT");
            onLogout.run();
        });

        header.getChildren().addAll(title, spacer, statusBox, adminPill, refreshBtn, logoutBtn);
        return header;
    }

    private HBox createMetricsRow() {
        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER);

        VBox cardOnline = createMetricCard("ONLINE USERS", "0", "#10b981", "🟢");
        onlineUsersVal = (Label) cardOnline.getChildren().get(1);

        VBox cardSessions = createMetricCard("ACTIVE SESSIONS", "0", "#6366f1", "🔑");
        activeSessionsVal = (Label) cardSessions.getChildren().get(1);

        VBox cardQueue = createMetricCard("PENDING QUEUE", "0", "#f59e0b", "⏳");
        pendingQueueVal = (Label) cardQueue.getChildren().get(1);

        VBox cardMessages = createMetricCard("MESSAGES TODAY", "0", "#06b6d4", "📨");
        messagesTodayVal = (Label) cardMessages.getChildren().get(1);

        row.getChildren().addAll(cardOnline, cardSessions, cardQueue, cardMessages);
        return row;
    }

    private VBox createMetricCard(String title, String initialVal, String accentColor, String icon) {
        VBox card = new VBox(4);
        card.getStyleClass().add("panel-card");
        card.setPadding(new Insets(14, 20, 14, 20));
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setAlignment(Pos.CENTER_LEFT);

        HBox top = new HBox(6);
        top.setAlignment(Pos.CENTER_LEFT);
        Label iconLbl = new Label(icon);
        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;");
        top.getChildren().addAll(iconLbl, titleLbl);

        Label valLbl = new Label(initialVal);
        valLbl.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: " + accentColor + ";");

        card.getChildren().addAll(top, valLbl);
        return card;
    }

    private VBox createUsersPane() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));

        // User Table
        userTable = new TableView<>();
        userTable.setItems(userList);
        userTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(userTable, Priority.ALWAYS);

        TableColumn<UserRow, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setMaxWidth(60);

        TableColumn<UserRow, String> userCol = new TableColumn<>("Username");
        userCol.setCellValueFactory(new PropertyValueFactory<>("username"));

        TableColumn<UserRow, String> emailCol = new TableColumn<>("Email");
        emailCol.setCellValueFactory(new PropertyValueFactory<>("email"));

        TableColumn<UserRow, String> roleCol = new TableColumn<>("Role");
        roleCol.setCellValueFactory(new PropertyValueFactory<>("role"));

        TableColumn<UserRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));

        TableColumn<UserRow, String> batchCol = new TableColumn<>("Batch");
        batchCol.setCellValueFactory(new PropertyValueFactory<>("batch"));

        userTable.getColumns().addAll(idCol, userCol, emailCol, roleCol, statusCol, batchCol);

        // Action Toolbar
        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_LEFT);

        Button banBtn = new Button("🚫 Ban User (Immediate Drop)");
        banBtn.getStyleClass().add("btn-danger");
        banBtn.setOnAction(e -> handleBanSelected());

        Button unbanBtn = new Button("✓ Activate / Unban");
        unbanBtn.getStyleClass().add("btn-success");
        unbanBtn.setOnAction(e -> handleUnbanSelected());

        ComboBox<Role> roleCombo = new ComboBox<>(FXCollections.observableArrayList(Role.STUDENT, Role.FACULTY, Role.ADMIN));
        roleCombo.setPromptText("Assign Role");

        Button roleBtn = new Button("Change Role");
        roleBtn.getStyleClass().add("btn-primary");
        roleBtn.setOnAction(e -> {
            Role selectedRole = roleCombo.getValue();
            if (selectedRole != null) {
                handleRoleChangeSelected(selectedRole);
            }
        });

        TextField batchInput = new TextField();
        batchInput.setPromptText("Batch Name/ID");
        batchInput.setPrefWidth(110);

        Button setBatchBtn = new Button("Assign Batch");
        setBatchBtn.getStyleClass().add("btn-secondary");
        setBatchBtn.setOnAction(e -> {
            UserRow selected = userTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Please select a user from the table to assign batch.");
                return;
            }
            if ("ADMIN".equalsIgnoreCase(selected.getRole())) {
                showError("Admin accounts cannot belong to an academic batch.");
                return;
            }
            String bId = batchInput.getText().trim();
            if (bId.isEmpty()) {
                showError("Please enter a valid Batch Name or ID (e.g. IT-2026).");
                return;
            }
            networkClient.send("ADMIN_SET_BATCH|" + selected.getUsername() + "|" + bId);
            batchInput.clear();
        });

        actions.getChildren().addAll(banBtn, unbanBtn, new Separator(javafx.geometry.Orientation.VERTICAL), roleCombo, roleBtn, new Separator(javafx.geometry.Orientation.VERTICAL), batchInput, setBatchBtn);

        box.getChildren().addAll(userTable, actions);
        return box;
    }

    private VBox createBatchesPane() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));

        // Batch Table
        batchTable = new TableView<>();
        batchTable.setItems(batchList);
        batchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(batchTable, Priority.ALWAYS);

        TableColumn<BatchRow, String> idCol = new TableColumn<>("Batch ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setMaxWidth(80);

        TableColumn<BatchRow, String> nameCol = new TableColumn<>("Batch Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));

        TableColumn<BatchRow, String> yearCol = new TableColumn<>("Year");
        yearCol.setCellValueFactory(new PropertyValueFactory<>("year"));

        batchTable.getColumns().addAll(idCol, nameCol, yearCol);

        // Create Batch Row
        HBox createRow = new HBox(10);
        createRow.setAlignment(Pos.CENTER_LEFT);

        Label lbl = new Label("Create New Batch:");
        lbl.getStyleClass().add("text-label");

        batchNameField = new TextField();
        batchNameField.setPromptText("Name (e.g. IT-2027-A)");

        batchYearField = new TextField();
        batchYearField.setPromptText("Year (e.g. 2027)");
        batchYearField.setPrefWidth(90);

        Button createBtn = new Button("Create Batch");
        createBtn.getStyleClass().add("btn-primary");
        createBtn.setOnAction(e -> handleCreateBatch());

        Button deleteBatchBtn = new Button("🗑️ Delete Selected Batch");
        deleteBatchBtn.getStyleClass().add("btn-danger");
        deleteBatchBtn.setOnAction(e -> handleDeleteBatch());

        createRow.getChildren().addAll(lbl, batchNameField, batchYearField, createBtn, new Separator(javafx.geometry.Orientation.VERTICAL), deleteBatchBtn);

        box.getChildren().addAll(batchTable, createRow);
        return box;
    }

    private void handleDeleteBatch() {
        BatchRow selected = batchTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Please select a batch from the table to delete.");
            return;
        }
        networkClient.send("ADMIN_DELETE_BATCH|" + selected.getId());
    }

    private void handleBanSelected() {
        UserRow selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Please select a user from the table to ban.");
            return;
        }
        networkClient.send("ADMIN_SET_STATUS|" + selected.getUsername() + "|BANNED");
    }

    private void handleUnbanSelected() {
        UserRow selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Please select a user from the table to unban.");
            return;
        }
        networkClient.send("ADMIN_SET_STATUS|" + selected.getUsername() + "|ACTIVE");
    }

    private void handleRoleChangeSelected(Role role) {
        UserRow selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Please select a user from the table to assign role.");
            return;
        }
        networkClient.send("ADMIN_SET_ROLE|" + selected.getUsername() + "|" + role.name());
    }

    private void handleCreateBatch() {
        String name = batchNameField.getText().trim();
        String year = batchYearField.getText().trim();
        if (name.isEmpty() || year.isEmpty()) {
            showError("Batch name and year are required.");
            return;
        }
        networkClient.send("ADMIN_CREATE_BATCH|" + name + "|" + year);
        batchNameField.clear();
        batchYearField.clear();
    }

    public void refreshData() {
        networkClient.send("ADMIN_STATS");
        networkClient.send("ADMIN_LIST_USERS");
        networkClient.send("ADMIN_LIST_BATCHES");
    }

    private void startPolling() {
        refreshData();
        // FR-16: Poll live statistics every 5 seconds
        pollingTimeline = new Timeline(new KeyFrame(Duration.seconds(5), e -> refreshData()));
        pollingTimeline.setCycleCount(Animation.INDEFINITE);
        pollingTimeline.play();
    }

    public void stopPolling() {
        if (pollingTimeline != null) {
            pollingTimeline.stop();
        }
    }

    public void handleIncomingLine(String line) {
        if (line.startsWith("STATS|")) {
            // STATS|onlineUsers|activeSessions|pendingQueue|messagesToday
            String[] parts = line.split("\\|", 5);
            if (parts.length >= 5) {
                onlineUsersVal.setText(parts[1]);
                activeSessionsVal.setText(parts[2]);
                pendingQueueVal.setText(parts[3]);
                messagesTodayVal.setText(parts[4]);
            }
        } else if (line.startsWith("USERS_START|")) {
            userList.clear();
        } else if (line.startsWith("USER_ROW|")) {
            // USER_ROW|userId|username|email|role|status|batchId
            String[] parts = line.split("\\|", 7);
            if (parts.length >= 7) {
                userList.add(new UserRow(parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]));
            }
        } else if (line.startsWith("BATCHES_START|")) {
            batchList.clear();
        } else if (line.startsWith("BATCH_ROW|")) {
            // BATCH_ROW|batchId|name|year
            String[] parts = line.split("\\|", 4);
            if (parts.length >= 4) {
                batchList.add(new BatchRow(parts[1], parts[2], parts[3]));
            }
        } else if (line.startsWith("OK|")) {
            showSuccess(line.substring(3));
            refreshData();
        } else if (line.startsWith("ERROR|")) {
            showError(line.substring(6));
        }
    }

    private void showError(String msg) {
        feedbackLabel.setText("⚠️ " + msg);
        feedbackLabel.setStyle("-fx-text-fill: #f87171; -fx-background-color: rgba(239, 68, 68, 0.15); " +
                "-fx-background-radius: 8px; -fx-padding: 8px 12px;");
        feedbackLabel.setVisible(true);
        feedbackLabel.setManaged(true);
    }

    private void showSuccess(String msg) {
        feedbackLabel.setText("✓ " + msg);
        feedbackLabel.setStyle("-fx-text-fill: #34d399; -fx-background-color: rgba(16, 185, 129, 0.15); " +
                "-fx-background-radius: 8px; -fx-padding: 8px 12px;");
        feedbackLabel.setVisible(true);
        feedbackLabel.setManaged(true);
    }

    public void updateConnectionStatus(NetworkClient.ConnectionStatus status) {
        statusCircle.setFill(Color.web(status.getColorHex()));
        statusText.setText(status.getLabel());
    }

    // JavaFX Table Row POJOs
    public static class UserRow {
        private final String id;
        private final String username;
        private final String email;
        private final String role;
        private final String status;
        private final String batch;

        public UserRow(String id, String username, String email, String role, String status, String batch) {
            this.id = id;
            this.username = username;
            this.email = email;
            this.role = role;
            this.status = status;
            this.batch = batch;
        }

        public String getId() { return id; }
        public String getUsername() { return username; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
        public String getStatus() { return status; }
        public String getBatch() { return batch; }
    }

    public static class BatchRow {
        private final String id;
        private final String name;
        private final String year;

        public BatchRow(String id, String name, String year) {
            this.id = id;
            this.name = name;
            this.year = year;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getYear() { return year; }
    }
}
