package com.campuschat.client.gui;

/**
 * Standard launcher workaround for JavaFX 11+ non-modular applications.
 * Running this main method directly avoids the "JavaFX runtime components are missing" error.
 */
public class CampusChatLauncher {

    public static void main(String[] args) {
        CampusChatApp.main(args);
    }
}
