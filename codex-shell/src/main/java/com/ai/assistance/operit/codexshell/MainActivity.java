package com.ai.assistance.operit.codexshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import io.noties.markwon.Markwon;

/** Separate thin-shell entry point; no imports from the legacy Operit Agent. */
public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private CodexAgentClient client;
    private EditText endpoint, token, input;
    private TextView status;
    private LinearLayout messages;
    private ScrollView scroll;
    private Button send, stop, fresh, history;
    private Markwon markdown;
    private JSONArray sessions = new JSONArray();
    private JSONObject current;
    private boolean busy;
    private boolean destroyed;
    private final File[] store = new File[1];

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        markdown = Markwon.create(this);
        store[0] = new File(getFilesDir(), "codex-sessions.json");
        try {
            if (store[0].exists()) sessions = new JSONArray(new String(Files.readAllBytes(store[0].toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            try { Files.copy(store[0].toPath(), new File(getFilesDir(), "codex-sessions.corrupt-"+System.currentTimeMillis()+".json").toPath()); }
            catch (Exception backupError) { finish(); return; }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 18);
        TextView title = new TextView(this); title.setText("Operit Codex · 独立 Agent 壳"); title.setTextSize(20); root.addView(title);
        endpoint = new EditText(this); endpoint.setSingleLine(true); endpoint.setHint("Backend SSE URL");
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(getPreferences(MODE_PRIVATE).getString("endpoint", "http://127.0.0.1:8088/v1/chat/stream")); root.addView(endpoint);
        token = new EditText(this); token.setSingleLine(true); token.setHint("后端访问令牌（不是 OpenAI Key；仅内存保存）");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(token);
        LinearLayout controls = new LinearLayout(this);
        fresh = button("新会话", controls); history = button("历史", controls); stop = button("停止", controls); stop.setEnabled(false); root.addView(controls);
        status = new TextView(this); status.setText("工具由 Codex 后端执行，壳只显示事件和回复"); root.addView(status);
        scroll = new ScrollView(this); messages = new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL); scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        input = new EditText(this); input.setHint("发送任务给 Codex"); input.setMinLines(2); input.setMaxLines(5); root.addView(input);
        send = button("发送", root); setContentView(root);
        if (sessions.length() > 0) current = sessions.optJSONObject(sessions.length()-1);
        if (current == null) newSession(); else { endpoint.setText(current.optString("endpoint", endpoint.getText().toString())); render(); }
        fresh.setOnClickListener(v -> newSession());
        history.setOnClickListener(v -> showHistory());
        stop.setOnClickListener(v -> {
            status.setText("正在停止…"); stop.setEnabled(false);
            CodexAgentClient active = client;
            if (active != null) executorCancel(active);
        });
        send.setOnClickListener(v -> sendMessage());
    }

    private Button button(String text, LinearLayout parent) {
        Button result = new Button(this); result.setText(text); parent.addView(result); return result;
    }
    private void executorCancel(CodexAgentClient active) { new Thread(active::cancel, "codex-cancel").start(); }
    private void newSession() {
        if (busy) return;
        try {
            current = new JSONObject().put("id", UUID.randomUUID().toString())
                .put("endpoint", endpoint.getText().toString().trim()).put("messages", new JSONArray());
            sessions.put(current); render(); persist(); status.setText("新会话");
        } catch (Exception e) { status.setText("无法创建会话"); }
    }
    private void showHistory() {
        if (busy) return;
        String[] titles = new String[sessions.length()];
        for (int i=0;i<titles.length;i++) {
            JSONObject s = sessions.optJSONObject(i);
            JSONArray m = s.optJSONArray("messages");
            titles[i] = (i+1)+" · "+(m.length()>0 ? m.optJSONObject(0).optString("text") : "空会话");
        }
        new AlertDialog.Builder(this).setTitle("Codex 会话").setItems(titles, (dialog, which) -> {
            current = sessions.optJSONObject(which);
            endpoint.setText(current.optString("endpoint"));
            token.setText(""); // Do not carry credentials across server/history changes.
            render(); status.setText("已切换会话");
        }).show();
    }
    private TextView text(String content) {
        TextView view = new TextView(this); view.setTextSize(16); view.setPadding(8,14,8,14); view.setTextIsSelectable(true);
        markdown.setMarkdown(view, content); messages.addView(view); return view;
    }
    private void render() {
        messages.removeAllViews();
        JSONArray list = current.optJSONArray("messages");
        if (list == null) return;
        for (int i=0;i<list.length();i++) {
            JSONObject entry = list.optJSONObject(i);
            text((entry.optString("role").equals("user") ? "**你**\n\n" : "**Codex**\n\n")+entry.optString("text"));
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void setBusy(boolean value) {
        busy = value; send.setEnabled(!value); fresh.setEnabled(!value); history.setEnabled(!value);
        endpoint.setEnabled(!value); token.setEnabled(!value); stop.setEnabled(value);
    }
    private void sendMessage() {
        if (busy || input.getText().toString().trim().isEmpty()) return;
        String url = endpoint.getText().toString().trim();
        if (!url.equals(current.optString("endpoint"))) newSession();
        String message = input.getText().toString();
        String bearer = token.getText().toString();
        String threadId = current.optString("thread_id", "");
        JSONObject selected = current;
        JSONObject answer = new JSONObject();
        try {
            JSONArray list = current.getJSONArray("messages");
            list.put(new JSONObject().put("role", "user").put("text", message));
            answer.put("role", "assistant").put("text", "").put("status", "running"); list.put(answer);
        } catch (Exception e) { status.setText("无法保存消息"); return; }
        input.setText(""); render();
        TextView answerView = (TextView) messages.getChildAt(messages.getChildCount()-1);
        StringBuilder response = new StringBuilder();
        setBusy(true); status.setText("Codex 正在处理…"); persist();
        getPreferences(MODE_PRIVATE).edit().putString("endpoint", url).apply();
        client = new CodexAgentClient(); CodexAgentClient active = client;
        executor.execute(() -> {
            String failure = null;
            try {
                active.stream(url, bearer, message, threadId, (type, data) -> runOnUiThread(() -> {
                    if (destroyed) return;
                    try {
                        if (type.equals("thread")) { selected.put("thread_id", data.getString("thread_id")); persist(); }
                        if (type.equals("delta")) {
                            response.append(data.optString("content")); answer.put("text", response.toString());
                            // Render only: no regex/XML tool extraction, package activation or host execution.
                            markdown.setMarkdown(answerView, "**Codex**\n\n"+response);
                            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                        }
                        if (type.equals("agent_event")) status.setText("Codex: "+data.optString("method"));
                        if (type.equals("done")) { answer.put("status", "completed"); status.setText("完成"); }
                    } catch (Exception e) { status.setText("事件格式错误"); }
                }));
            } catch (Exception e) { failure = e.getMessage(); }
            String result = failure;
            runOnUiThread(() -> {
                if (destroyed) return;
                if (result != null) {
                    try { answer.put("status", "incomplete"); } catch (Exception ignored) {}
                    status.setText("未完成："+result);
                }
                setBusy(false); persist(); client = null;
            });
        });
    }
    private void persist() {
        try {
            File temp = new File(getFilesDir(), "codex-sessions.tmp");
            Files.write(temp.toPath(), sessions.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), store[0].toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { status.setText("会话保存失败；请勿关闭应用"); }
    }
    @Override protected void onDestroy() {
        destroyed = true; persist();
        CodexAgentClient active = client; if (active != null) executorCancel(active);
        executor.shutdownNow(); super.onDestroy();
    }
}