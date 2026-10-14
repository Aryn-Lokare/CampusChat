package com.campuschat.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

/**
 * Interactive Console Client for testing real-time chat with the Campus Chat Server.
 */
public class ConsoleClient {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        System.out.println("==================================================");
        System.out.println("      Campus Chat System - Console Client         ");
        System.out.println("==================================================");
        System.out.println("Connecting to " + host + ":" + port + "...");

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("[CONNECTED] Connected to Campus Chat Server!");
            System.out.println("\nCommands available:");
            System.out.println("  login <user> <pass>       - Log in to your account");
            System.out.println("  chat <batchId> <message>  - Send message to your batch room");
            System.out.println("  logout                    - Log out from current session");
            System.out.println("  exit                      - Disconnect and exit client");
            System.out.println("  Or type raw commands (e.g. LOGIN|alice|password)\n");

            // Background reader thread for incoming server messages
            Thread readerThread = new Thread(() -> {
                try {
                    String serverLine;
                    while ((serverLine = in.readLine()) != null) {
                        displayServerMessage(serverLine);
                    }
                } catch (Exception e) {
                    System.out.println("[DISCONNECTED] Connection to server lost.");
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            // Main input loop
            while (scanner.hasNextLine()) {
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    break;
                }

                if (input.startsWith("login ")) {
                    String[] parts = input.split("\\s+", 3);
                    if (parts.length >= 3) {
                        out.println("LOGIN|" + parts[1] + "|" + parts[2]);
                    } else {
                        System.out.println("[USAGE] login <username> <password>");
                    }
                } else if (input.startsWith("chat ")) {
                    String[] parts = input.split("\\s+", 3);
                    if (parts.length >= 3) {
                        out.println("CHAT|" + parts[1] + "|" + parts[2]);
                    } else {
                        System.out.println("[USAGE] chat <batchId> <message>");
                    }
                } else if (input.equalsIgnoreCase("logout")) {
                    out.println("LOGOUT");
                } else if (!input.isEmpty()) {
                    // Send raw line
                    out.println(input);
                }
            }

            System.out.println("Exiting client...");
        } catch (Exception e) {
            System.err.println("[ERROR] Connection error: " + e.getMessage());
        }
    }

    private static void displayServerMessage(String line) {
        if (line.startsWith("MSG|")) {
            // MSG|type|sender|batchId|content|timestamp
            String[] parts = line.split("\\|", 6);
            if (parts.length >= 5) {
                String type = parts[1];
                String sender = parts[2];
                String content = parts[4];
                System.out.println("\n[" + type + "] " + sender + ": " + content);
                return;
            }
        } else if (line.startsWith("OK|")) {
            System.out.println("\n[SERVER OK] " + line.substring(3));
            return;
        } else if (line.startsWith("ERROR|")) {
            System.out.println("\n[SERVER ERROR] " + line.substring(6));
            return;
        }

        System.out.println("\n[SERVER] " + line);
    }
}
