package com.campuschat.client.gui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Authentication View providing Login, Registration, and Email Verification tabs.
 */
public class AuthView extends BorderPane {

    private final NetworkClient networkClient;
    private final Consumer<String[]> onLoginSuccess; // args: [username, role, batchId, sessionToken]

    private Label feedbackLabel;
    private StackPane contentArea;

    // Login fields
    private TextField loginUserField;
    private PasswordField loginPassField;

    // Register fields
    private ComboBox<String> regRoleCombo;
    private TextField regUserField;
    private TextField regEmailField;
    private PasswordField regPassField;
    private Label batchLbl;
    private TextField regBatchField;

    // Verify fields
    private TextField verifyEmailField;
    private TextField verifyTokenField;

    private Button tabLoginBtn;
    private Button tabRegBtn;
    private Button tabVerifyBtn;

    public AuthView(NetworkClient networkClient, Consumer<String[]> onLoginSuccess) {
        this.networkClient = networkClient;
        this.onLoginSuccess = onLoginSuccess;
        getStyleClass().add("app-container");

        initUI();
    }

    private void initUI() {
        setPadding(new Insets(30));

        // Center card container
        VBox card = new VBox(20);
        card.getStyleClass().add("panel-card");
        card.setMaxWidth(460);
        card.setPadding(new Insets(32, 36, 36, 36));
        card.setAlignment(Pos.CENTER);

        // Header Branding
        VBox header = new VBox(6);
        header.setAlignment(Pos.CENTER);

        Label logoLabel = new Label("🎓 Campus Chat");
        logoLabel.getStyleClass().add("text-title");
        logoLabel.setStyle("-fx-font-size: 24px; -fx-text-fill: #818cf8;");

        Label subtitleLabel = new Label("Real-time Batch-scoped Campus Communication");
        subtitleLabel.getStyleClass().add("text-subtitle");

        header.getChildren().addAll(logoLabel, subtitleLabel);

        // Tab Switcher
        HBox tabBox = new HBox(10);
        tabBox.setAlignment(Pos.CENTER);
        tabBox.setPadding(new Insets(10, 0, 10, 0));

        tabLoginBtn = new Button("Sign In");
        tabRegBtn = new Button("Register");
        tabVerifyBtn = new Button("Verify Email");

        tabLoginBtn.setOnAction(e -> switchTab(0));
        tabRegBtn.setOnAction(e -> switchTab(1));
        tabVerifyBtn.setOnAction(e -> switchTab(2));

        tabBox.getChildren().addAll(tabLoginBtn, tabRegBtn, tabVerifyBtn);

        // Feedback / Status alert label
        feedbackLabel = new Label();
        feedbackLabel.setWrapText(true);
        feedbackLabel.setAlignment(Pos.CENTER);
        feedbackLabel.setVisible(false);
        feedbackLabel.setManaged(false);
        feedbackLabel.setMaxWidth(400);

        // Content Area (StackPane holding views)
        contentArea = new StackPane();
        contentArea.getChildren().addAll(createLoginPane(), createRegisterPane(), createVerifyPane());

        card.getChildren().addAll(header, tabBox, feedbackLabel, contentArea);

        setCenter(card);
        switchTab(0);
    }

    private VBox createLoginPane() {
        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER_LEFT);

        Label userLbl = new Label("Username or Institutional Email");
        userLbl.getStyleClass().add("text-label");
        loginUserField = new TextField();
        loginUserField.setPromptText("e.g. aryan or student@vcet.edu.in");

        Label passLbl = new Label("Password");
        passLbl.getStyleClass().add("text-label");
        loginPassField = new PasswordField();
        loginPassField.setPromptText("Enter your password");

        loginPassField.setOnAction(e -> handleLogin());

        Button submitBtn = new Button("Sign In to Batch");
        submitBtn.getStyleClass().add("btn-primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setOnAction(e -> handleLogin());

        box.getChildren().addAll(userLbl, loginUserField, passLbl, loginPassField, new Region(), submitBtn);
        return box;
    }

    private VBox createRegisterPane() {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER_LEFT);

        Label roleLbl = new Label("Register As");
        roleLbl.getStyleClass().add("text-label");
        regRoleCombo = new ComboBox<>(FXCollections.observableArrayList("Student", "Faculty"));
        regRoleCombo.setValue("Student");
        regRoleCombo.setMaxWidth(Double.MAX_VALUE);

        Label userLbl = new Label("Choose Username");
        userLbl.getStyleClass().add("text-label");
        regUserField = new TextField();
        regUserField.setPromptText("e.g. aryan_lokare");

        Label emailLbl = new Label("Institutional Email (@vcet.edu.in / @college.edu)");
        emailLbl.getStyleClass().add("text-label");
        regEmailField = new TextField();
        regEmailField.setPromptText("e.g. aryan@vcet.edu.in");

        Label passLbl = new Label("Password");
        passLbl.getStyleClass().add("text-label");
        regPassField = new PasswordField();
        regPassField.setPromptText("At least 6 characters");

        batchLbl = new Label("Batch Name (Academic Room) *Required*");
        batchLbl.getStyleClass().add("text-label");
        regBatchField = new TextField();
        regBatchField.setPromptText("e.g. IT-2026");

        regRoleCombo.setOnAction(e -> {
            if ("Faculty".equalsIgnoreCase(regRoleCombo.getValue())) {
                batchLbl.setText("Batch Name (Academic Room) [Optional for Faculty]");
                regBatchField.setPromptText("Leave empty or specify batch (e.g. IT-2026)");
            } else {
                batchLbl.setText("Batch Name (Academic Room) *Required*");
                regBatchField.setPromptText("e.g. IT-2026");
            }
        });

        Button submitBtn = new Button("Register Account");
        submitBtn.getStyleClass().add("btn-primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setOnAction(e -> handleRegister());

        box.getChildren().addAll(
                roleLbl, regRoleCombo,
                userLbl, regUserField,
                emailLbl, regEmailField,
                passLbl, regPassField,
                batchLbl, regBatchField,
                new Region(), submitBtn
        );
        return box;
    }

    private VBox createVerifyPane() {
        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER_LEFT);

        Label emailLbl = new Label("Registered Email Address");
        emailLbl.getStyleClass().add("text-label");
        verifyEmailField = new TextField();
        verifyEmailField.setPromptText("e.g. student@vcet.edu.in");

        Label tokenLbl = new Label("6-Digit Verification Code");
        tokenLbl.getStyleClass().add("text-label");
        verifyTokenField = new TextField();
        verifyTokenField.setPromptText("e.g. 123456");

        verifyTokenField.setOnAction(e -> handleVerify());

        Button verifyBtn = new Button("Activate & Verify Account");
        verifyBtn.getStyleClass().add("btn-success");
        verifyBtn.setMaxWidth(Double.MAX_VALUE);
        verifyBtn.setOnAction(e -> handleVerify());

        Button resendBtn = new Button("Resend Verification Code");
        resendBtn.getStyleClass().add("btn-secondary");
        resendBtn.setMaxWidth(Double.MAX_VALUE);
        resendBtn.setOnAction(e -> handleResend());

        box.getChildren().addAll(
                emailLbl, verifyEmailField,
                tokenLbl, verifyTokenField,
                new Region(), verifyBtn, resendBtn
        );
        return box;
    }

    private void switchTab(int tabIndex) {
        clearFeedback();
        tabLoginBtn.getStyleClass().removeAll("tab-button-active", "tab-button-inactive");
        tabRegBtn.getStyleClass().removeAll("tab-button-active", "tab-button-inactive");
        tabVerifyBtn.getStyleClass().removeAll("tab-button-active", "tab-button-inactive");

        tabLoginBtn.getStyleClass().add(tabIndex == 0 ? "tab-button-active" : "tab-button-inactive");
        tabRegBtn.getStyleClass().add(tabIndex == 1 ? "tab-button-active" : "tab-button-inactive");
        tabVerifyBtn.getStyleClass().add(tabIndex == 2 ? "tab-button-active" : "tab-button-inactive");

        for (int i = 0; i < contentArea.getChildren().size(); i++) {
            contentArea.getChildren().get(i).setVisible(i == tabIndex);
            contentArea.getChildren().get(i).setManaged(i == tabIndex);
        }
    }

    private void handleLogin() {
        String username = loginUserField.getText().trim();
        String password = loginPassField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Please enter both username/email and password.");
            return;
        }

        if (!networkClient.isConnected()) {
            networkClient.connect();
        }

        clearFeedback();
        networkClient.send("LOGIN|" + username + "|" + password);
    }

    private void handleRegister() {
        String role = regRoleCombo.getValue() != null ? regRoleCombo.getValue() : "Student";
        String user = regUserField.getText().trim();
        String email = regEmailField.getText().trim();
        String pass = regPassField.getText().trim();
        String batch = regBatchField.getText().trim();

        if (user.isEmpty() || email.isEmpty() || pass.isEmpty()) {
            showError("Username, institutional email, and password are required.");
            return;
        }

        if ("Student".equalsIgnoreCase(role) && batch.isEmpty()) {
            showError("Batch Name is required for student registration. It cannot be empty.");
            return;
        }

        if (!networkClient.isConnected()) {
            networkClient.connect();
        }

        clearFeedback();
        networkClient.send("REGISTER|" + user + "|" + email + "|" + pass + "|" + batch + "|" + role.toUpperCase());
    }

    private void handleVerify() {
        String email = verifyEmailField.getText().trim();
        String token = verifyTokenField.getText().trim();

        if (token.isEmpty()) {
            showError("Please enter the 6-digit verification code.");
            return;
        }

        if (!networkClient.isConnected()) {
            networkClient.connect();
        }

        clearFeedback();
        networkClient.send("VERIFY|" + (email.isEmpty() ? "" : email + "|") + token);
    }

    private void handleResend() {
        String email = verifyEmailField.getText().trim();
        if (email.isEmpty()) {
            showError("Please enter your registered institutional email to resend.");
            return;
        }

        if (!networkClient.isConnected()) {
            networkClient.connect();
        }

        clearFeedback();
        networkClient.send("RESEND_VERIFY|" + email);
    }

    public void handleServerResponse(String line) {
        if (line.startsWith("OK|")) {
            String payload = line.substring(3);
            if (payload.equals("VERIFY_SENT")) {
                showSuccess("Verification code sent! Please check your institutional email.");
                if (!regEmailField.getText().trim().isEmpty()) {
                    verifyEmailField.setText(regEmailField.getText().trim());
                }
                switchTab(2); // Switch to verify tab
            } else if (payload.equals("VERIFIED")) {
                showSuccess("Email verified successfully! You can now sign in.");
                switchTab(0); // Switch to login tab
            } else {
                // Must be session token from LOGIN
                String sessionToken = payload;
                networkClient.setSavedSessionToken(sessionToken);
                // Trigger success callback
                String loginUser = loginUserField.getText().trim();
                onLoginSuccess.accept(new String[]{loginUser, sessionToken});
            }
        } else if (line.startsWith("ERROR|")) {
            showError(line.substring(6));
        }
    }

    private void showError(String message) {
        feedbackLabel.setText("⚠️ " + message);
        feedbackLabel.setStyle("-fx-text-fill: #f87171; -fx-background-color: rgba(239, 68, 68, 0.15); " +
                "-fx-background-radius: 8px; -fx-padding: 8px 12px; -fx-font-size: 12px;");
        feedbackLabel.setVisible(true);
        feedbackLabel.setManaged(true);
    }

    private void showSuccess(String message) {
        feedbackLabel.setText("✓ " + message);
        feedbackLabel.setStyle("-fx-text-fill: #34d399; -fx-background-color: rgba(16, 185, 129, 0.15); " +
                "-fx-background-radius: 8px; -fx-padding: 8px 12px; -fx-font-size: 12px;");
        feedbackLabel.setVisible(true);
        feedbackLabel.setManaged(true);
    }

    private void clearFeedback() {
        feedbackLabel.setText("");
        feedbackLabel.setVisible(false);
        feedbackLabel.setManaged(false);
    }
}
