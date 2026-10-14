package com.ai.assistance.operit.codexshell;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Standalone Agent transport, NOT an Operit LLM provider or host-tool executor. */
public final class CodexAgentClient {
    public interface Listener { void event(String type, JSONObject data); }
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    public void stream(String endpoint, String token, String message, String threadId,
                       Listener listener) throws Exception {
        if (cancelled) throw new IOException("Cancelled");
        URL url = new URL(endpoint);
        boolean local = url.getHost().equals("127.0.0.1") || url.getHost().equals("localhost");
        if (!url.getProtocol().equals("https") && !(local && url.getProtocol().equals("http")))
            throw new IOException("Remote backend requires HTTPS; HTTP is allowed only on localhost");
        JSONObject request = new JSONObject().put("message", message);
        if (threadId != null && !threadId.isEmpty()) request.put("thread_id", threadId);
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        active = connection;
        if (cancelled) { connection.disconnect(); active = null; throw new IOException("Cancelled"); }
        boolean[] done = {false};
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            if (connection.getResponseCode() != 200)
                throw new IOException("Backend HTTP " + connection.getResponseCode());
            if (!String.valueOf(connection.getContentType()).startsWith("text/event-stream"))
                throw new IOException("Backend did not return SSE");
            try (InputStreamReader input = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                SseReader.read(input, (type, data) -> {
                    if (cancelled) throw new IOException("Cancelled");
                    JSONObject event;
                    try { event = new JSONObject(data); }
                    catch (Exception e) { throw new IOException("Invalid SSE JSON", e); }
                    if (type.equals("error")) throw new IOException(event.optString("error", "Agent failed"));
                    if (type.equals("done")) {
                        if (!event.optString("status").equals("completed")) throw new IOException("Agent did not complete");
                        done[0] = true;
                    }
                    listener.event(type, event);
                    return !done[0];
                });
            }
            if (!done[0]) throw new IOException(cancelled ? "Cancelled" : "Stream disconnected before completion");
        } finally {
            connection.disconnect();
            active = null;
        }
    }
}