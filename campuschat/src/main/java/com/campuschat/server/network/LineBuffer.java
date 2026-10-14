package com.campuschat.server.network;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-connection line-framing buffer.
 * Extracts newline-terminated (\n) lines from raw TCP stream chunks.
 * Enforces a strict 4 KB maximum line length limit (PRD Section 6 / 13)
 * to prevent memory exhaustion and buffer overflow attacks.
 */
public class LineBuffer {

    public static final int MAX_LINE_BYTES = 4096; // 4 KB limit
    private final ByteArrayOutputStream lineAccumulator = new ByteArrayOutputStream(512);

    /**
     * Appends readable bytes from the provided ByteBuffer and extracts complete lines.
     *
     * @param buffer incoming direct or heap ByteBuffer read from SocketChannel
     * @return List of complete, decoded String lines (stripped of trailing \r\n)
     * @throws LineTooLongException if a single line without a newline exceeds 4 KB
     */
    public List<String> processBytes(ByteBuffer buffer) throws LineTooLongException {
        List<String> lines = new ArrayList<>();

        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b == '\n') {
                byte[] rawBytes = lineAccumulator.toByteArray();
                lineAccumulator.reset();

                String line = new String(rawBytes, StandardCharsets.UTF_8);
                // Strip optional carriage return from \r\n
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                lines.add(line);
            } else {
                lineAccumulator.write(b);
                if (lineAccumulator.size() > MAX_LINE_BYTES) {
                    throw new LineTooLongException("Protocol line exceeded maximum allowed length of " + MAX_LINE_BYTES + " bytes");
                }
            }
        }

        return lines;
    }

    public static class LineTooLongException extends Exception {
        public LineTooLongException(String message) {
            super(message);
        }
    }
}
