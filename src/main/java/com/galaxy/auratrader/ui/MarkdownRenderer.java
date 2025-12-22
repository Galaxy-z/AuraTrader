package com.galaxy.auratrader.ui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.emoji.EmojiExtension;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.util.Arrays;

/**
 * Utility to convert Markdown to sanitized HTML and produce a configured JEditorPane.
 */
public class MarkdownRenderer {

    // Enable common useful extensions: tables (for GitHub-style tables), autolinks and emoji
    private static final Parser PARSER = Parser.builder()
            .extensions(Arrays.asList(
                    TablesExtension.create(),
                    AutolinkExtension.create(),
                    EmojiExtension.create()
            ))
            .build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(Arrays.asList(
                    TablesExtension.create(),
                    EmojiExtension.create(),
                    AutolinkExtension.create()
            ))
            .build();

    /**
     * Render Markdown to sanitized HTML fragment (body-only).
     */
    public static String renderToHtml(String markdown) {
        if (markdown == null) return "";
        Node document = PARSER.parse(markdown);
        String html = RENDERER.render(document);
        // Sanitize using a relaxed safelist but allow basic tags used in Markdown (strong, em, code, pre, ul, ol, li, a, img)
        // Also explicitly allow table-related tags and common attributes so tables produced by the TablesExtension are preserved.
        return Jsoup.clean(html, Safelist.relaxed()
                .addTags("pre", "code", "table", "thead", "tbody", "tr", "th", "td", "caption")
                .addAttributes("img", "src", "alt", "width", "height")
                .addAttributes("a", "href", "title")
                .addAttributes("table", "border", "cellpadding", "cellspacing", "style")
                .addAttributes("td", "th", "style")
        );
    }

    /**
     * Create a non-editable JEditorPane configured for rendering sanitized HTML content.
     * Caller can call setText(html) or use setDocument()/insert methods.
     */
    public static JEditorPane createEditorPane() {
        JEditorPane editor = new JEditorPane();
        editor.setContentType("text/html");
        editor.setEditable(false);

        // Configure a simple stylesheet for better readability
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet styleSheet = kit.getStyleSheet();
        // Prefer a color-emoji-capable font when available (Windows: Segoe UI Emoji). Fall back to Arial/sans-serif.
        styleSheet.addRule("body { font-family: 'Segoe UI Emoji', Arial, sans-serif; padding: 6px; }");
        styleSheet.addRule("pre { background: #f6f6f6; padding: 8px; border-radius: 4px; }");
        styleSheet.addRule("code { font-family: Consolas, Monaco, monospace; background:#eee; padding:2px 4px; border-radius:4px; }");
        styleSheet.addRule("h1,h2,h3 { color: #333; }");
        // Basic table styles so Markdown tables are readable in the JEditorPane
        styleSheet.addRule("table { border-collapse: collapse; margin: 6px 0; }");
        styleSheet.addRule("th, td { border: 1px solid #ddd; padding: 6px; }");

        editor.setEditorKit(kit);
        editor.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        // Set an explicit font that prefers emoji-capable face; Font will fall back if not available on the system.
        editor.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 12));

        return editor;
    }

    /**
     * Helper to set HTML content safely into a JEditorPane, preserving existing document.
     */
    public static void setHtmlSafe(JEditorPane editor, String htmlFragment) {
        if (editor == null) return;
        String full = "<html><head><meta charset='utf-8'></head><body>" + (htmlFragment == null ? "" : htmlFragment) + "</body></html>";
        // setText is simplest and reliable
        SwingUtilities.invokeLater(() -> editor.setText(full));
    }
}
