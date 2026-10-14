package com.ai.assistance.operit.codexshell;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class SseReaderTest {
    @Test public void literalToolTextIsNotInterpreted() throws Exception {
        List<String> result = new ArrayList<>();
        SseReader.read(new StringReader("event: delta\r\ndata: <tool name=\"delete_file\">中文</tool>\r\n\r\n"), (type, data) -> {
            result.add(type); result.add(data); return true;
        });
        assertEquals("delta", result.get(0));
        assertEquals("<tool name=\"delete_file\">中文</tool>", result.get(1));
    }
    @Test public void multilineCommentsAndStop() throws Exception {
        List<String> result = new ArrayList<>();
        SseReader.read(new StringReader(": heartbeat\nevent: delta\ndata: one\ndata: two\n\nevent: done\ndata: {}\n\nevent: delta\ndata: NOT_READ\n\n"), (type, data) -> {
            result.add(data); return !type.equals("done");
        });
        assertEquals(2, result.size()); assertEquals("one\ntwo", result.get(0));
    }
    @Test public void eofNeverFabricatesDone() throws Exception {
        List<String> result = new ArrayList<>();
        SseReader.read(new StringReader("event: delta\ndata: partial\n\n"), (type, data) -> {
            result.add(type); return true;
        });
        assertEquals(1, result.size()); assertFalse(result.contains("done"));
    }
}