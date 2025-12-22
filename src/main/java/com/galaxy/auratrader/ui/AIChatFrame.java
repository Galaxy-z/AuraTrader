package com.galaxy.auratrader.ui;

import com.galaxy.auratrader.llm.chat.Chatter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

@Component
@Profile("!test")
public class AIChatFrame extends JFrame {
    private final Chatter chatter;

    private JTextPane chatPane;
    private StyledDocument doc;
    private JTextField inputField;
    private JButton sendButton;

    public AIChatFrame(Chatter chatter) {
        this.chatter = chatter;
        initUI();
    }

    private void initUI() {
        setTitle("AI Chat");
        setSize(600, 400);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        doc = chatPane.getStyledDocument();
        JScrollPane scrollPane = new JScrollPane(chatPane);
        add(scrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Send");
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
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
    }

    private void sendPrompt() {
        String prompt = inputField.getText().trim();
        if (prompt.isEmpty()) return;

        // Append user message
        appendStyledText("User: " + prompt + "\n", userStyle());
        inputField.setText("");

        // Disable input to prevent concurrent requests
        inputField.setEnabled(false);
        sendButton.setEnabled(false);

        // Subscribe to the new streaming tool-aware API and append events as they arrive
        chatter.streamToolCall(prompt, true).subscribe(ev -> {
            // ev.type is an enum: REASONING, CONTENT, TOOL_CALL, TOOL_RESULT, FINAL, ERROR
            switch (ev.type) {
                case REASONING:
                    SwingUtilities.invokeLater(() -> appendStyledText(ev.text, reasoningStyle()));
                    break;
                case CONTENT:
                    SwingUtilities.invokeLater(() -> appendStyledText(ev.text, contentStyle()));
                    break;
                case TOOL_CALL:
                    SwingUtilities.invokeLater(() -> appendStyledText("[Tool call: " + (ev.toolName == null ? "" : ev.toolName) + "] " + (ev.text == null ? "" : ev.text) + "\n", toolCallStyle()));
                    break;
                case TOOL_RESULT:
                    SwingUtilities.invokeLater(() -> appendStyledText("[Tool result: " + (ev.toolName == null ? "" : ev.toolName) + "] " + (ev.text == null ? "" : ev.text) + "\n", toolResultStyle()));
                    break;
                case FINAL:
                    SwingUtilities.invokeLater(() -> {
                        appendStyledText("\nAI: " + (ev.text == null ? "" : ev.text) + "\n", finalStyle());
                        // re-enable input
                        inputField.setEnabled(true);
                        sendButton.setEnabled(true);
                    });
                    break;
                case ERROR:
                    SwingUtilities.invokeLater(() -> {
                        appendStyledText("[Error] " + (ev.text == null ? ev.toolName : ev.text) + "\n", errorStyle());
                        // re-enable input on error
                        inputField.setEnabled(true);
                        sendButton.setEnabled(true);
                    });
                    break;
            }
        }, error -> {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(AIChatFrame.this, "AI error: " + error.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                inputField.setEnabled(true);
                sendButton.setEnabled(true);
            });
        }, () -> {
            // onComplete: ensure inputs enabled
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

    // Utility: append text with style and auto-scroll
    private void appendStyledText(String text, AttributeSet style) {
        try {
            doc.insertString(doc.getLength(), text, style);
            chatPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            // Shouldn't happen, but log if necessary
            e.printStackTrace();
        }
    }

    private AttributeSet userStyle() {
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
