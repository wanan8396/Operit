package com.ai.assistance.operit.codexshell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Transport framing only. Never interprets response text as tools/XML. */
public final class SseReader {
    public interface Listener { boolean event(String type, String data) throws IOException; }
    public static void read(Reader reader, Listener listener) throws IOException {
        BufferedReader lines = new BufferedReader(reader);
        String type = "message";
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.length() > 1024 * 1024) throw new IOException("SSE line too large");
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    data.setLength(data.length() - 1);
                    if (!listener.event(type, data.toString())) return;
                }
                type = "message";
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) continue;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (field.equals("event")) type = value;
            if (field.equals("data")) {
                data.append(value).append('\n');
                if (data.length() > 1024 * 1024) throw new IOException("SSE event too large");
            }
        }
        // EOF does not mean successful agent completion. Caller requires explicit done.
    }
    private SseReader() {}
}