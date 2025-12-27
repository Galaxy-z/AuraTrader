package com.galaxy.auratrader.ui;

import com.galaxy.auratrader.llm.chat.Chatter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("!test")
public class AIChatFrame extends JFrame {
    private final Chatter chatter;

    // HTML buffers: preHtmlBuffer holds committed HTML; liveFragments holds ongoing partial fragments in order
    private JEditorPane chatPane;
    private StringBuilder preHtmlBuffer = new StringBuilder();
    private final List<LiveFragment> liveFragments = new ArrayList<>();
    private JTextField inputField;
    private JButton sendButton;
    // toggle for deep thinking / reasoner mode
    private JCheckBox deepThinkingCheckBox;

    // Store full contents of collapsible items keyed by id; chatPane will show a preview and a link that triggers a dialog
    private final Map<String, String> collapsibleStore = new HashMap<>();
    private int collapsibleCounter = 0;

    // --- New fields for automation tab ---
    private JTabbedPane tabbedPane;
    private JPanel automationPanel;
    private JTextField autoPromptField;
    private JSpinner intervalSpinner; // seconds
    private JButton startStopAutoButton;
    private JLabel countdownLabel;
    private javax.swing.Timer countdownTimer; // Swing timer for UI countdown
    private volatile boolean autoRunning = false;
    private int remainingSeconds = 0;
    // track consecutive errors and a max threshold to avoid infinite noisy retries
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    // -------------------------------------

    // Maximum number of characters to render into the editor to keep UI responsive.
    private static final int MAX_DISPLAY_CHARS = 20_000;

    public AIChatFrame(Chatter chatter) {
        this.chatter = chatter;
        initUI();
    }

    private void initUI() {
        setTitle("AI Chat");
        setSize(600, 400);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // create HTML-capable chat pane
        chatPane = MarkdownRenderer.createEditorPane();
        chatPane.setText("<html><body></body></html>");
        // add hyperlink listener to handle our custom action:show:<id> links
        chatPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                String desc = e.getDescription();
                if (desc != null && desc.startsWith("action:show:")) {
                    String id = desc.substring("action:show:".length());
                    String content = collapsibleStore.get(id);
                    if (content != null) {
                        SwingUtilities.invokeLater(() -> {
                            JTextArea ta = new JTextArea(content);
                            ta.setEditable(false);
                            ta.setLineWrap(true);
                            ta.setWrapStyleWord(true);
                            ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                            JScrollPane sp = new JScrollPane(ta);
                            sp.setPreferredSize(new Dimension(800, 600));
                            JOptionPane.showMessageDialog(AIChatFrame.this, sp, "详细内容", JOptionPane.PLAIN_MESSAGE);
                        });
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(chatPane);

        // Create a tabbed pane and add existing chat as the first tab (自由问答)
        tabbedPane = new JTabbedPane();
        JPanel qaPanel = new JPanel(new BorderLayout());
        qaPanel.add(scrollPane, BorderLayout.CENTER);
        tabbedPane.addTab("自由问答", qaPanel);

        // --- Build automation tab ---
        automationPanel = new JPanel(new BorderLayout());
        JPanel topForm = new JPanel(new BorderLayout(6, 6));
        autoPromptField = new JTextField();
        autoPromptField.setToolTipText("自动化提示词（每次循环使用）");
        topForm.add(new JLabel("提示词:"), BorderLayout.WEST);
        topForm.add(autoPromptField, BorderLayout.CENTER);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        intervalSpinner = new JSpinner(new SpinnerNumberModel(60, 1, 86400, 1));
        intervalSpinner.setToolTipText("间隔时间（秒），计时从模型完成后开始");
        rightControls.add(new JLabel("间隔(s):"));
        rightControls.add(intervalSpinner);

        startStopAutoButton = new JButton("开始");
        rightControls.add(startStopAutoButton);

        countdownLabel = new JLabel("倒计时: -");
        rightControls.add(countdownLabel);

        topForm.add(rightControls, BorderLayout.EAST);

        automationPanel.add(topForm, BorderLayout.NORTH);
        // Instruction area in center
        JTextArea instr = new JTextArea("自动化执行：输入提示词，设置间隔（秒），点击开始。注意：每次调用会等待模型输出结束（FINAL/onComplete）再开始计时。输出会写入聊天窗口。");
        instr.setEditable(false);
        instr.setLineWrap(true);
        instr.setWrapStyleWord(true);
        instr.setBackground(UIManager.getColor("Panel.background"));
        instr.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
        automationPanel.add(instr, BorderLayout.CENTER);

        tabbedPane.addTab("自动化执行", automationPanel);

        add(tabbedPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Send");
        // create a right-side panel to hold the deep thinking toggle and the send button
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        deepThinkingCheckBox = new JCheckBox("深度思考", true);
        deepThinkingCheckBox.setToolTipText("启用模型的深度思考/推理模式（可能更慢但更有条理）");
        rightPanel.add(deepThinkingCheckBox);
        rightPanel.add(sendButton);

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(rightPanel, BorderLayout.EAST);
        add(inputPanel, BorderLayout.SOUTH);

        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendPrompt();
            }
        });

        inputField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendPrompt();
            }
        });

        // Automation button action
        startStopAutoButton.addActionListener(e -> {
            if (!autoRunning) {
                startAutomation();
            } else {
                stopAutomation();
            }
        });
    }

    private void sendPrompt() {
        String prompt = inputField.getText().trim();
        if (prompt.isEmpty()) return;

        // Append user message as escaped HTML into committed buffer
        preHtmlBuffer.append("<div><strong style='color:#0B66C3'>User:</strong> ").append(escapeHtml(prompt)).append("</div>");
        // Immediately add an "AI:" label on the next line so it appears while the model streams
        preHtmlBuffer.append(MarkdownRenderer.renderToHtml("**AI:**"));
        // refresh view
        renderCurrentBuffer();
        inputField.setText("");

        // Disable input to prevent concurrent requests
        inputField.setEnabled(false);
        sendButton.setEnabled(false);

        // Subscribe to the new streaming tool-aware API and append events as they arrive
        boolean enableThinking = deepThinkingCheckBox != null && deepThinkingCheckBox.isSelected();
        chatter.streamToolCall(prompt, enableThinking).subscribe(ev -> {
            // ev.type is an enum: REASONING, CONTENT, TOOL_CALL, TOOL_RESULT, FINAL, ERROR
            switch (ev.type) {
                case REASONING:
                    // reasoning fragments are appended to liveFragments (ordered)
                    if (ev.isPartial) {
                        appendToLiveFragment(LiveFragment.Type.REASONING, ev.text);
                        renderCurrentBuffer();
                    } else {
                        appendToLiveFragment(LiveFragment.Type.REASONING, ev.text);
                        // finalize only reasoning fragments (commit as gray italic)
                        flushLiveFragmentsOfType(LiveFragment.Type.REASONING);
                        renderCurrentBuffer();
                    }
                    break;
                case CONTENT:
                    // content fragments are now treated like reasoning (live concatenation)
                    if (ev.isPartial) {
                        appendToLiveFragment(LiveFragment.Type.CONTENT, ev.text);
                        renderCurrentBuffer();
                    } else {
                        appendToLiveFragment(LiveFragment.Type.CONTENT, ev.text);
                        // commit content fragment as rendered Markdown
                        flushLiveFragmentsOfType(LiveFragment.Type.CONTENT);
                        renderCurrentBuffer();
                    }
                    break;
                case TOOL_CALL:
                    // before tool display, flush any in-progress fragments to preserve order
                    flushLiveFragments();
                    // use collapsible rendering for potentially large tool payloads
                    preHtmlBuffer.append(makeCollapsibleHtml("[Tool call: " + escapeHtml(ev.toolName == null ? "" : ev.toolName) + "]", ev.text == null ? "" : ev.text, "#6A1B9A"));
                    renderCurrentBuffer();
                    break;
                case TOOL_RESULT:
                    flushLiveFragments();
                    preHtmlBuffer.append(makeCollapsibleHtml("[Tool result: " + escapeHtml(ev.toolName == null ? "" : ev.toolName) + "]", ev.text == null ? "" : ev.text, "#2E7D32"));
                    renderCurrentBuffer();
                    break;
                case FINAL:
                    // FINAL no longer needs to push content because CONTENT already covers it.
                    // Just finalize any remaining fragments and re-enable input.
                    flushLiveFragments();
                    renderCurrentBuffer();
                    SwingUtilities.invokeLater(() -> {
                        inputField.setEnabled(true);
                        sendButton.setEnabled(true);
                    });
                    break;
                case ERROR:
                    flushLiveFragments();
                    preHtmlBuffer.append(makeCollapsibleHtml("[Error]", ev.text == null ? ev.toolName == null ? "" : ev.toolName : ev.text, "#FF0000"));
                    renderCurrentBuffer();
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(AIChatFrame.this, "AI error: " + errorMessageOrTool(ev), "Error", JOptionPane.ERROR_MESSAGE);
                        inputField.setEnabled(true);
                        sendButton.setEnabled(true);
                    });
                    break;
            }
        }, error -> {
            // on error, flush fragments and surface dialog
            flushLiveFragments();
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(AIChatFrame.this, "AI error: " + error.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                inputField.setEnabled(true);
                sendButton.setEnabled(true);
            });
        }, () -> {
            // onComplete: ensure inputs enabled
            flushLiveFragments();
            SwingUtilities.invokeLater(() -> {
                inputField.setEnabled(true);
                sendButton.setEnabled(true);
            });
        });

        // Make sure window is visible
        if (!isVisible()) {
            setVisible(true);
        }
    }

    // helper to pick readable message from event
    private String errorMessageOrTool(Object ev) {
        try {
            // ev is not available here in this method use-case, keep simple placeholder
            return "处理错误";
        } catch (Exception ex) {
            return "处理错误";
        }
    }

    // --- New automation methods ---
    private void startAutomation() {
        String prompt = autoPromptField.getText().trim();
        if (prompt.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请填写提示词。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        autoRunning = true;
        startStopAutoButton.setText("停止");
        // Ensure inputs in automation tab are disabled while running
        autoPromptField.setEnabled(false);
        intervalSpinner.setEnabled(false);
        // reset error counter when starting
        consecutiveErrors = 0;
        // Kick off first run immediately
        runAutomationOnce();
    }

    private void stopAutomation() {
        autoRunning = false;
        startStopAutoButton.setText("开始");
        autoPromptField.setEnabled(true);
        intervalSpinner.setEnabled(true);
        stopCountdown();
        // reset error counter when stopped
        consecutiveErrors = 0;
    }

    private void runAutomationOnce() {
        if (!autoRunning) return;
        String prompt = autoPromptField.getText().trim();
        if (prompt.isEmpty()) {
            stopAutomation();
            return;
        }
        // Append user message and AI label
        preHtmlBuffer.append("<div><strong style='color:#0B66C3'>User:</strong> ").append(escapeHtml(prompt)).append("</div>");
        preHtmlBuffer.append(MarkdownRenderer.renderToHtml("**AI:**"));
        renderCurrentBuffer();

        boolean enableThinking = deepThinkingCheckBox != null && deepThinkingCheckBox.isSelected();
        // Start streaming. We rely on the subscribe onComplete to start the countdown (i.e., wait for model to finish)
        chatter.streamToolCall(prompt, enableThinking).subscribe(ev -> {
            switch (ev.type) {
                case REASONING:
                    if (ev.isPartial) {
                        appendToLiveFragment(LiveFragment.Type.REASONING, ev.text);
                        renderCurrentBuffer();
                    } else {
                        appendToLiveFragment(LiveFragment.Type.REASONING, ev.text);
                        flushLiveFragmentsOfType(LiveFragment.Type.REASONING);
                        renderCurrentBuffer();
                    }
                    break;
                case CONTENT:
                    if (ev.isPartial) {
                        appendToLiveFragment(LiveFragment.Type.CONTENT, ev.text);
                        renderCurrentBuffer();
                    } else {
                        appendToLiveFragment(LiveFragment.Type.CONTENT, ev.text);
                        flushLiveFragmentsOfType(LiveFragment.Type.CONTENT);
                        renderCurrentBuffer();
                    }
                    break;
                case TOOL_CALL:
                    flushLiveFragments();
                    preHtmlBuffer.append(makeCollapsibleHtml("[Tool call: " + escapeHtml(ev.toolName == null ? "" : ev.toolName) + "]", ev.text == null ? "" : ev.text, "#6A1B9A"));
                    renderCurrentBuffer();
                    break;
                case TOOL_RESULT:
                    flushLiveFragments();
                    preHtmlBuffer.append(makeCollapsibleHtml("[Tool result: " + escapeHtml(ev.toolName == null ? "" : ev.toolName) + "]", ev.text == null ? "" : ev.text, "#2E7D32"));
                    renderCurrentBuffer();
                    break;
                case FINAL:
                    flushLiveFragments();
                    renderCurrentBuffer();
                    // When model signals FINAL, start the countdown to next run
                    // reset consecutive errors on successful completion
                    consecutiveErrors = 0;
                    SwingUtilities.invokeLater(() -> startCountdown());
                    break;
                case ERROR:
                    flushLiveFragments();
                    preHtmlBuffer.append(makeCollapsibleHtml("[Error]", ev.text == null ? ev.toolName == null ? "" : ev.toolName : ev.text, "#FF0000"));
                    renderCurrentBuffer();
                    // increment error counter and decide whether to continue or stop
                    consecutiveErrors++;
                    if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                        // schedule next attempt after configured interval
                        SwingUtilities.invokeLater(() -> startCountdown());
                    } else {
                        // too many consecutive errors -> stop automation and notify user
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(AIChatFrame.this, "自动化执行失败次数过多，已停止（" + consecutiveErrors + " 次）。", "Error", JOptionPane.ERROR_MESSAGE);
                            stopAutomation();
                        });
                    }
                    break;
            }
        }, error -> {
            // subscription-level error: similar handling as event-level ERROR
            flushLiveFragments();
            preHtmlBuffer.append(makeCollapsibleHtml("[Error]", error.getMessage() == null ? "" : error.getMessage(), "#FF0000"));
            renderCurrentBuffer();
            consecutiveErrors++;
            if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                SwingUtilities.invokeLater(() -> startCountdown());
            } else {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(AIChatFrame.this, "自动化执行订阅出错，已停止（" + consecutiveErrors + " 次）。\n原因: " + error.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    stopAutomation();
                });
            }
        }, () -> {
            // onComplete: ensure countdown started if not already (some providers may call onComplete instead of FINAL)
            flushLiveFragments();
            // treat onComplete as success and reset error counter
            consecutiveErrors = 0;
            SwingUtilities.invokeLater(() -> startCountdown());
        });
    }

    private void startCountdown() {
        // Cancel existing timer first
        stopCountdown();
        if (!autoRunning) return;
        Object val = intervalSpinner.getValue();
        int secs = 60;
        if (val instanceof Number) secs = ((Number) val).intValue();
        remainingSeconds = Math.max(0, secs);
        countdownLabel.setText("倒计时: " + remainingSeconds + "s");
        countdownTimer = new javax.swing.Timer(1000, e -> {
            remainingSeconds--;
            if (remainingSeconds < 0) remainingSeconds = 0;
            countdownLabel.setText("倒计时: " + remainingSeconds + "s");
            if (remainingSeconds <= 0) {
                // stop timer and trigger next run
                stopCountdown();
                if (autoRunning) {
                    // Run next iteration on EDT to keep UI consistent
                    SwingUtilities.invokeLater(() -> runAutomationOnce());
                }
            }
        });
        countdownTimer.setRepeats(true);
        countdownTimer.start();
    }

    private void stopCountdown() {
        if (countdownTimer != null) {
            countdownTimer.stop();
            countdownTimer = null;
        }
        countdownLabel.setText("倒计时: -");
    }
    // --- End automation methods ---

    // render preHtmlBuffer + live reasoning span (if any) into the editor
    private void renderCurrentBuffer() {
        StringBuilder full = new StringBuilder();
        full.append(preHtmlBuffer.toString());
        // append live fragments in-order as inline spans
        for (LiveFragment f : liveFragments) {
            if (f.type == LiveFragment.Type.REASONING) {
                if (!f.text.isEmpty()) {
                    full.append("<span style='color:gray;font-style:italic'>").append(escapeHtml(f.text.toString())).append("</span>");
                }
            } else if (f.type == LiveFragment.Type.CONTENT) {
                if (!f.text.isEmpty()) {
                    // Render live content fragments as Markdown so markdown appears in real-time
                    full.append(MarkdownRenderer.renderToHtml(f.text.toString()));
                }
            }
        }
        // Truncate the HTML for display to keep the UI responsive while preserving the full buffer internally.
        String displayHtml = truncateHtmlTail(full.toString(), MAX_DISPLAY_CHARS);
        MarkdownRenderer.setHtmlSafe(chatPane, displayHtml);
        SwingUtilities.invokeLater(() -> chatPane.setCaretPosition(chatPane.getDocument().getLength()));
    }

    // Truncate the HTML to the trailing portion not exceeding maxChars. Try to cut at a <div boundary
    // so we don't break tags. If we had to truncate, prepend a small notice.
    private String truncateHtmlTail(String html, int maxChars) {
        if (html == null) return "";
        if (html.length() <= maxChars) return html;
        int startSearch = Math.max(0, html.length() - maxChars);
        // look for last occurrence of a message-like start (<div) within the tail region
        int idx = html.lastIndexOf("<div", html.length() - 1);
        if (idx >= startSearch) {
            String tail = html.substring(idx);
            String notice = "<div style='color:gray;font-size:small'>&hellip; (仅显示最近部分)</div>";
            return notice + tail;
        }
        // fallback: just take the last maxChars chars and prepend a notice
        String tail = html.substring(html.length() - maxChars);
        String notice = "<div style='color:gray;font-size:small'>&hellip; (仅显示最近部分)</div>";
        return notice + tail;
    }

    // Flush all live fragments (commit them into preHtmlBuffer) preserving order
    private void flushLiveFragments() {
        if (liveFragments.isEmpty()) return;
        for (LiveFragment f : liveFragments) {
            String text = f.text.toString();
            if (text.isEmpty()) continue;
            if (f.type == LiveFragment.Type.REASONING) {
                preHtmlBuffer.append("<div style='color:gray;font-style:italic'>").append(escapeHtml(text)).append("</div>");
            } else if (f.type == LiveFragment.Type.CONTENT) {
                // render content as Markdown when committing
                preHtmlBuffer.append(MarkdownRenderer.renderToHtml(text));
            }
        }
        liveFragments.clear();
    }

    // Flush only fragments of a specific type (useful when finalizing that type)
    private void flushLiveFragmentsOfType(LiveFragment.Type type) {
        if (liveFragments.isEmpty()) return;
        List<LiveFragment> remaining = new ArrayList<>();
        for (LiveFragment f : liveFragments) {
            if (f.type == type) {
                String text = f.text.toString();
                if (text.isEmpty()) continue;
                if (type == LiveFragment.Type.REASONING) {
                    preHtmlBuffer.append("<div style='color:gray;font-style:italic'>").append(escapeHtml(text)).append("</div>");
                } else if (type == LiveFragment.Type.CONTENT) {
                    preHtmlBuffer.append(MarkdownRenderer.renderToHtml(text));
                }
            } else {
                remaining.add(f);
            }
        }
        liveFragments.clear();
        liveFragments.addAll(remaining);
    }

    // Append text to the last live fragment of the same type, or create a new one
    private void appendToLiveFragment(LiveFragment.Type type, String text) {
        if (text == null) return;
        if (!liveFragments.isEmpty()) {
            LiveFragment last = liveFragments.get(liveFragments.size() - 1);
            if (last.type == type) {
                last.text.append(text);
                return;
            }
        }
        LiveFragment nf = new LiveFragment(type, text == null ? "" : text);
        liveFragments.add(nf);
    }

    // Simple HTML escaping for text inserted into HTML fragments
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    // New helper: render a label + content, collapsing if the content is large or multi-line
    private String makeCollapsibleHtml(String labelHtml, String content, String colorHex) {
        if (labelHtml == null) labelHtml = "";
        if (content == null) content = "";
        String escaped = escapeHtml(content);
        // decide whether to collapse: long text or many lines
        int lineCount = content.split("\r\n|\r|\n", -1).length;
        boolean shouldCollapse = escaped.length() > 300 || lineCount > 6;
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='color:").append(colorHex).append(";'>");
        if (shouldCollapse) {
            // store the full content and render a short preview with a custom action link that the HyperlinkListener will handle
            String id = "coll-" + (++collapsibleCounter);
            collapsibleStore.put(id, content);
            // build a short preview: up to 3 lines or first 200 chars
            String[] lines = content.split("\r\n|\r|\n", -1);
            String preview;
            if (lines.length > 3) {
                StringBuilder p = new StringBuilder();
                for (int i = 0; i < 3; i++) {
                    if (i > 0) p.append("\n");
                    p.append(lines[i]);
                }
                preview = p.toString();
            } else {
                preview = content;
            }
            if (preview.length() > 200) preview = preview.substring(0, 200) + "...";

            sb.append("<div><strong>").append(labelHtml).append("</strong> ")
              .append("<span>").append(escapeHtml(preview)).append("</span> ")
              .append("<a href='action:show:").append(id).append("' style='text-decoration:none;color:").append(colorHex).append(";margin-left:6px;'>[显示更多]</a>")
              .append("</div>");
        } else {
            sb.append("<div>").append(labelHtml).append(" ").append(escaped).append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    // Small helper to keep live fragments ordered and typed
    private static class LiveFragment {
        enum Type { REASONING, CONTENT }
        final Type type;
        final StringBuilder text = new StringBuilder();

        LiveFragment(Type type, String initial) {
            this.type = type;
            if (initial != null) this.text.append(initial);
        }
    }

    private AttributeSet userStyle() { // kept for compatibility but not used now
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, new Color(0x0B66C3)); // blue
        StyleConstants.setBold(s, true);
        return s;
    }

    private AttributeSet reasoningStyle() {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, Color.GRAY);
        StyleConstants.setItalic(s, true);
        return s;
    }

    private AttributeSet contentStyle() {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, Color.BLACK);
        return s;
    }

    private AttributeSet toolCallStyle() {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, new Color(0x6A1B9A)); // purple
        StyleConstants.setItalic(s, true);
        return s;
    }

    private AttributeSet toolResultStyle() {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, new Color(0x2E7D32)); // green
        return s;
    }

    private AttributeSet finalStyle() {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, Color.BLACK);
        StyleConstants.setBold(s, true);
        return s;
    }

    private AttributeSet errorStyle() {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, Color.RED);
        return s;
    }
}
