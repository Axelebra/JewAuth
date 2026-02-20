package dev.tokenlogin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.CharacterVisitor;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Client-side nick hider. Intercepts ALL text rendering to replace
 * the player's real name with a fake one. Never touches the Session.
 *
 * Provides replacement methods for all 3 text types Minecraft uses:
 * String, Text (component tree), and OrderedText (render-ready).
 */
@Environment(EnvType.CLIENT)
public class NickHider {

    private static volatile boolean enabled   = false;
    private static volatile String  fakeName  = "";
    private static volatile String  realName  = "";

    private static volatile String statusMessage = "";
    private static volatile int    statusColor   = 0xFF888888;

    public static boolean isEnabled()        { return enabled && !fakeName.isEmpty(); }
    public static String  getFakeName()      { return fakeName; }
    public static String  getRealName()      { return realName; }
    public static String  getStatusMessage() { return statusMessage; }
    public static int     getStatusColor()   { return statusColor; }

    public static void enable(String newFakeName) {
        if (newFakeName == null || newFakeName.isBlank()) {
            statusMessage = "Enter a name first";
            statusColor   = 0xFFFF5555;
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        realName  = mc.getSession().getUsername();
        fakeName  = newFakeName.trim();
        enabled   = true;

        statusMessage = "Hidden as: " + fakeName;
        statusColor   = 0xFF55FF55;
        TokenLoginClient.LOGGER.info("NickHider enabled: {} -> {}", realName, fakeName);
    }

    public static void disable() {
        enabled       = false;
        fakeName      = "";
        realName      = "";
        statusMessage = "Hider disabled";
        statusColor   = 0xFF888888;
    }

    // =====================================================================
    // String replacement (used by DrawContext.drawText(String) path)
    // =====================================================================

    public static String replaceInString(String input) {
        if (!isEnabled() || input == null || realName.isEmpty()) return input;
        if (!input.contains(realName)) return input;
        return input.replace(realName, fakeName);
    }

    // =====================================================================
    // Text replacement — uses visitor to preserve all styles/colors
    // =====================================================================

    public static Text replaceInText(Text input) {
        if (!isEnabled() || input == null || realName.isEmpty()) return input;
        if (!input.getString().contains(realName)) return input;

        // Walk the text tree; visitor gives us each leaf as (Style, String)
        MutableText result = Text.empty();
        input.visit((Style style, String content) -> {
            String replaced = content.replace(realName, fakeName);
            result.append(Text.literal(replaced).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);

        return result;
    }

    // =====================================================================
    // OrderedText replacement — for scoreboard, tooltips, etc.
    //
    // Collects all (style, codepoint) pairs, builds the full string,
    // does replacement, then rebuilds a new OrderedText with styles
    // mapped to the correct positions.
    // =====================================================================

    public static OrderedText replaceInOrdered(OrderedText input) {
        if (!isEnabled() || input == null || realName.isEmpty()) return input;

        // Collect every character with its style
        List<Style> styles = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        input.accept((int index, Style style, int codePoint) -> {
            sb.appendCodePoint(codePoint);
            styles.add(style);
            return true;
        });

        String original = sb.toString();
        if (!original.contains(realName)) return input;

        String replaced = original.replace(realName, fakeName);

        // Build a style array for the replaced string.
        // For each occurrence of realName, the replacement characters
        // inherit the style of the first character of that occurrence.
        List<Style> newStyles = buildReplacedStyles(original, replaced, styles);

        // Build a new OrderedText from the replaced string + styles
        return buildOrderedText(replaced, newStyles);
    }

    /**
     * Maps styles from the original string onto the replaced string.
     * Characters outside replacement zones keep their original style.
     * Replacement characters inherit the style from the start of the match.
     */
    private static List<Style> buildReplacedStyles(String original, String replaced, List<Style> origStyles) {
        List<Style> result = new ArrayList<>(replaced.length());

        int origIdx = 0;
        int repIdx  = 0;

        while (origIdx < original.length() && repIdx < replaced.length()) {
            int matchPos = original.indexOf(realName, origIdx);

            if (matchPos < 0 || matchPos > origIdx + (replaced.length() - repIdx)) {
                // No more matches — copy remaining styles 1:1
                while (origIdx < original.length() && repIdx < replaced.length()) {
                    result.add(origIdx < origStyles.size() ? origStyles.get(origIdx) : Style.EMPTY);
                    origIdx++;
                    repIdx++;
                }
                break;
            }

            // Copy styles for characters before this match
            while (origIdx < matchPos) {
                result.add(origIdx < origStyles.size() ? origStyles.get(origIdx) : Style.EMPTY);
                origIdx++;
                repIdx++;
            }

            // For the replacement characters, use the style at matchPos
            Style matchStyle = matchPos < origStyles.size() ? origStyles.get(matchPos) : Style.EMPTY;
            for (int i = 0; i < fakeName.length(); i++) {
                result.add(matchStyle);
                repIdx++;
            }
            origIdx += realName.length();
        }

        // Pad if replaced string is longer
        while (result.size() < replaced.length()) {
            result.add(Style.EMPTY);
        }

        return result;
    }

    /**
     * Builds an OrderedText from a string + per-character styles.
     */
    private static OrderedText buildOrderedText(String text, List<Style> styles) {
        return (CharacterVisitor visitor) -> {
            for (int i = 0; i < text.length(); i++) {
                Style style = i < styles.size() ? styles.get(i) : Style.EMPTY;
                if (!visitor.accept(i, style, text.codePointAt(i))) {
                    return false;
                }
            }
            return true;
        };
    }
}