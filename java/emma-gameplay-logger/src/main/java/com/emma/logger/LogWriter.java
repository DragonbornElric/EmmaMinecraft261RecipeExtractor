package com.emma.logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Manages JSONL file output for a single player session.
 * Creates a session directory with state_stream.jsonl and event_stream.jsonl.
 * Thread-safe: all writes are synchronized.
 */
public class LogWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger("EmmaLogger");
    private static final Gson GSON = new Gson();

    private final Path sessionDir;
    private final String sessionId;
    private BufferedWriter stateWriter;
    private BufferedWriter eventWriter;
    private int stateEventCount = 0;
    private int eventEventCount = 0;
    private int totalEvents = 0;
    private boolean closed = false;

    public LogWriter(Path sessionDir, String sessionId) {
        this.sessionDir = sessionDir;
        this.sessionId = sessionId;
    }

    /**
     * Open the JSONL files for writing. Must be called before any write operations.
     */
    public synchronized void open() throws IOException {
        Files.createDirectories(sessionDir);

        stateWriter = Files.newBufferedWriter(
                sessionDir.resolve("state_stream.jsonl"),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        eventWriter = Files.newBufferedWriter(
                sessionDir.resolve("event_stream.jsonl"),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        LOGGER.info("[EmmaLogger] Session {} opened at {}", sessionId, sessionDir);
    }

    /**
     * Write a state snapshot to state_stream.jsonl.
     */
    public synchronized void writeState(JsonObject snapshot) {
        if (closed || stateWriter == null) return;
        try {
            snapshot.addProperty("ms", System.currentTimeMillis());
            stateWriter.write(GSON.toJson(snapshot));
            stateWriter.newLine();
            stateEventCount++;
            totalEvents++;
            if (stateEventCount % LogConfig.flushInterval == 0) {
                stateWriter.flush();
            }
        } catch (IOException e) {
            LOGGER.error("[EmmaLogger] Failed to write state: {}", e.getMessage());
        }
    }

    /**
     * Write an action event to event_stream.jsonl.
     */
    public synchronized void writeEvent(JsonObject event) {
        if (closed || eventWriter == null) return;
        try {
            event.addProperty("ms", System.currentTimeMillis());
            eventWriter.write(GSON.toJson(event));
            eventWriter.newLine();
            eventEventCount++;
            totalEvents++;
            if (eventEventCount % LogConfig.flushInterval == 0) {
                eventWriter.flush();
            }
        } catch (IOException e) {
            LOGGER.error("[EmmaLogger] Failed to write event: {}", e.getMessage());
        }
    }

    /**
     * Write session metadata to metadata.json.
     */
    public synchronized void writeMetadata(JsonObject metadata) {
        try {
            Path metaPath = sessionDir.resolve("metadata.json");
            Files.writeString(metaPath, new Gson().newBuilder().setPrettyPrinting()
                    .create().toJson(metadata));
        } catch (IOException e) {
            LOGGER.error("[EmmaLogger] Failed to write metadata: {}", e.getMessage());
        }
    }

    /**
     * Flush and close all writers.
     */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (stateWriter != null) {
                stateWriter.flush();
                stateWriter.close();
            }
            if (eventWriter != null) {
                eventWriter.flush();
                eventWriter.close();
            }
            LOGGER.info("[EmmaLogger] Session {} closed. Total events: {} (state: {}, action: {})",
                    sessionId, totalEvents, stateEventCount, eventEventCount);
        } catch (IOException e) {
            LOGGER.error("[EmmaLogger] Failed to close writers: {}", e.getMessage());
        }
    }

    public int getTotalEvents() {
        return totalEvents;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Path getSessionDir() {
        return sessionDir;
    }
}
