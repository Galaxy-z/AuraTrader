package com.galaxy.auratrader.ui;

import com.galaxy.auratrader.llm.chat.Chatter;
import io.github.pigmesh.ai.deepseek.core.chat.Delta;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

        // Subscribe to stream and append chunks as they arrive
        AtomicBoolean reasoningFinished = new AtomicBoolean(false);
        chatter.streamChatFlux(prompt).subscribe(response -> {
            Delta delta = response.choices().get(0).delta();
            if (delta.reasoningContent() != null) {
                SwingUtilities.invokeLater(() -> appendStyledText(delta.reasoningContent(), reasoningStyle()));
            }
            if (delta.content() != null) {
                if (!reasoningFinished.get()) {
                    SwingUtilities.invokeLater(() -> appendStyledText("\n", reasoningStyle()));
                    reasoningFinished.set(true);
                }
                SwingUtilities.invokeLater(() -> appendStyledText(delta.content(), contentStyle()));
            }
        }, error -> {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(AIChatFrame.this, "AI error: " + error.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
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
}
