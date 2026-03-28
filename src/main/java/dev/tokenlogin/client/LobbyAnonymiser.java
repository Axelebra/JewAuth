package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.CharacterVisitor;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Always-on anonymiser. Replaces Hypixel server/lobby codes and profile IDs
 * with JEWBZ / discord.gg/jewbz across scoreboard, tab list, and chat.
 *
 * Uses lookbehinds wherever possible so only the replaced portion inherits the
 * replacement style — the surrounding text (e.g. "Server: ") keeps its original colour.
 *
 * Patterns operate on PLAIN text (§ codes are already parsed into styles).
 */
@Environment(EnvType.CLIENT)
public class LobbyAnonymiser {

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
        // Scoreboard bottom line: "11/15/24 m19CJ" / "03/22/26 L21F" → fake date + JEW
        new Rule(Pattern.compile("\\d+/\\d+/\\d+\\s+[a-zA-Z]\\w+"), "09/11/2001 JEW"),
        // Tab SERVER widget:   "Server: mini31JD"  → only server name replaced
        new Rule(Pattern.compile("(?<=Server: )\\S+"), "JEWBZ"),
        // Tab PROFILE widget:  "Profile: Banana"   → only profile name replaced
        new Rule(Pattern.compile("(?<=Profile: )\\S+"), "JEWBZ"),
        // Chat profile UUID:   "Profile ID: 2eafa9..." → only UUID replaced
        new Rule(Pattern.compile("(?<=Profile ID: )[0-9a-fA-F\\-]{32,36}"), "discord.gg/jewbz"),
        // Chat profile join:   "You are playing on profile: Banana" → only name replaced
        new Rule(Pattern.compile("(?<=You are playing on profile: )\\S+"), "JEWBZ"),
        // Chat server transfer: "Sending to server mini27B..." → only server name replaced
        new Rule(Pattern.compile("(?<=Sending to server )[^\\s.]+"), "JEWBZ"),
        // Chat hub join: "Request join for Hub #1 (mega12D)..." → server name in parens
        new Rule(Pattern.compile("(?<=\\()(?:mini|mega)\\w+(?=\\))"), "JEWBZ")
    );

    // =========================================================================
    // Public API — returns same instance when nothing matched (caller can use ==)
    // =========================================================================

    public static String replaceInString(String input) {
        if (input == null) return null;
        String out = applyAll(input);
        return out.equals(input) ? input : out;
    }

    /**
     * Text component path (chat, etc.).
     * Preserves per-character styles by walking the text via its styled visitor.
     */
    public static Text replaceInText(Text input) {
        if (input == null) return null;

        List<Style> charStyles = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        // visit() resolves inherited styles and gives (resolvedStyle, leafContent) pairs
        input.visit((Style style, String content) -> {
            for (int i = 0; i < content.length(); i++) {
                charStyles.add(style);
            }
            sb.append(content);
            return Optional.empty();
        }, Style.EMPTY);

        String original = sb.toString();
        List<Segment> segments = collectSegments(original);
        if (segments.isEmpty()) return input;

        return buildText(original, charStyles, segments);
    }

    /**
     * OrderedText path (scoreboard sidebar, tab list widget lines).
     * Preserves per-character styles via the CharacterVisitor.
     */
    public static OrderedText replaceInOrdered(OrderedText input) {
        if (input == null) return input;

        List<Style> styles = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        input.accept((int index, Style style, int codePoint) -> {
            sb.appendCodePoint(codePoint);
            styles.add(style);
            return true;
        });

        String original = sb.toString();
        List<Segment> segments = collectSegments(original);
        if (segments.isEmpty()) return input;

        return buildOrderedText(original, styles, segments);
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private record Segment(int start, int end, String replacement) {}

    private static String applyAll(String s) {
        for (Rule rule : RULES) {
            s = rule.pattern().matcher(s).replaceAll(rule.replacement());
        }
        return s;
    }

    /** Collects all non-overlapping rule matches sorted by start position. */
    private static List<Segment> collectSegments(String source) {
        List<Segment> list = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(source);
            while (m.find()) {
                list.add(new Segment(m.start(), m.end(), rule.replacement()));
            }
        }
        list.sort(Comparator.comparingInt(Segment::start));
        // Drop overlapping entries — keep the earlier match
        List<Segment> deduped = new ArrayList<>();
        int lastEnd = -1;
        for (Segment seg : list) {
            if (seg.start >= lastEnd) {
                deduped.add(seg);
                lastEnd = seg.end;
            }
        }
        return deduped;
    }

    /**
     * Builds a new Text component from the replacement plan.
     * Characters kept from the original carry their original style.
     * Replacement characters inherit the style of the first matched character.
     * Consecutive same-style characters are grouped into one literal node.
     */
    private static Text buildText(String original, List<Style> charStyles, List<Segment> segments) {
        StringBuilder newStr = new StringBuilder();
        List<Style> newStyles = new ArrayList<>();
        int pos = 0;

        for (Segment seg : segments) {
            if (seg.start < pos) continue;
            // Keep original chars before this match
            while (pos < seg.start) {
                newStr.append(original.charAt(pos));
                newStyles.add(pos < charStyles.size() ? charStyles.get(pos) : Style.EMPTY);
                pos++;
            }
            // Replacement inherits the style of the first char of the match
            Style repStyle = seg.start < charStyles.size() ? charStyles.get(seg.start) : Style.EMPTY;
            for (int i = 0; i < seg.replacement.length(); i++) {
                newStr.append(seg.replacement.charAt(i));
                newStyles.add(repStyle);
            }
            pos = seg.end;
        }
        // Trailing original chars
        while (pos < original.length()) {
            newStr.append(original.charAt(pos));
            newStyles.add(pos < charStyles.size() ? charStyles.get(pos) : Style.EMPTY);
            pos++;
        }

        String result = newStr.toString();
        if (result.isEmpty()) return Text.empty();

        // Group consecutive same-style characters into styled literal nodes
        MutableText out = Text.empty();
        Style currentStyle = newStyles.get(0);
        StringBuilder group = new StringBuilder();
        for (int i = 0; i < result.length(); i++) {
            Style s = i < newStyles.size() ? newStyles.get(i) : Style.EMPTY;
            if (!s.equals(currentStyle)) {
                out.append(Text.literal(group.toString()).setStyle(currentStyle));
                group = new StringBuilder();
                currentStyle = s;
            }
            group.append(result.charAt(i));
        }
        if (!group.isEmpty()) {
            out.append(Text.literal(group.toString()).setStyle(currentStyle));
        }
        return out;
    }

    /**
     * Builds a new OrderedText from the replacement plan.
     * Same style-preservation logic as buildText but for the render-ready path.
     */
    private static OrderedText buildOrderedText(String original, List<Style> styles, List<Segment> segments) {
        StringBuilder newStr = new StringBuilder();
        List<Style> newStyles = new ArrayList<>();
        int pos = 0;

        for (Segment seg : segments) {
            if (seg.start < pos) continue;
            while (pos < seg.start) {
                newStr.appendCodePoint(original.codePointAt(pos));
                newStyles.add(pos < styles.size() ? styles.get(pos) : Style.EMPTY);
                pos++;
            }
            Style repStyle = seg.start < styles.size() ? styles.get(seg.start) : Style.EMPTY;
            for (int i = 0; i < seg.replacement.length(); ) {
                int cp = seg.replacement.codePointAt(i);
                newStr.appendCodePoint(cp);
                newStyles.add(repStyle);
                i += Character.charCount(cp);
            }
            pos = seg.end;
        }
        while (pos < original.length()) {
            newStr.appendCodePoint(original.codePointAt(pos));
            newStyles.add(pos < styles.size() ? styles.get(pos) : Style.EMPTY);
            pos++;
        }

        String result = newStr.toString();
        return (CharacterVisitor visitor) -> {
            int charIdx = 0;
            for (int i = 0; i < result.length(); ) {
                int cp = result.codePointAt(i);
                Style s = charIdx < newStyles.size() ? newStyles.get(charIdx) : Style.EMPTY;
                if (!visitor.accept(charIdx, s, cp)) return false;
                i += Character.charCount(cp);
                charIdx++;
            }
            return true;
        };
    }
}
