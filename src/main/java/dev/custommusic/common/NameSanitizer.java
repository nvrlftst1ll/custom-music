package dev.custommusic.common;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns arbitrary file names into safe ResourceLocation path segments ([a-z0-9_.-]),
 * transliterating Cyrillic so «Мой Крутой Трек.mp3» becomes {@code moy_krutoy_trek}.
 */
public final class NameSanitizer {
    private static final Map<String, String> CYRILLIC = Map.ofEntries(
        Map.entry("а", "a"), Map.entry("б", "b"), Map.entry("в", "v"), Map.entry("г", "g"),
        Map.entry("д", "d"), Map.entry("е", "e"), Map.entry("ё", "yo"), Map.entry("ж", "zh"),
        Map.entry("з", "z"), Map.entry("и", "i"), Map.entry("й", "y"), Map.entry("к", "k"),
        Map.entry("л", "l"), Map.entry("м", "m"), Map.entry("н", "n"), Map.entry("о", "o"),
        Map.entry("п", "p"), Map.entry("р", "r"), Map.entry("с", "s"), Map.entry("т", "t"),
        Map.entry("у", "u"), Map.entry("ф", "f"), Map.entry("х", "h"), Map.entry("ц", "ts"),
        Map.entry("ч", "ch"), Map.entry("ш", "sh"), Map.entry("щ", "sch"), Map.entry("ъ", ""),
        Map.entry("ы", "y"), Map.entry("ь", ""), Map.entry("э", "e"), Map.entry("ю", "yu"),
        Map.entry("я", "ya"));

    private NameSanitizer() {
    }

    public static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Human-readable title shown in tooltips: file name without extension. */
    public static String titleOf(String fileName) {
        return stripExtension(fileName).trim();
    }

    /** Safe, deterministic slug for a file name. */
    public static String slugOf(String fileName) {
        String base = stripExtension(fileName).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(base.length());
        base.codePoints().forEach(cp -> {
            String s = new String(Character.toChars(cp));
            String mapped = CYRILLIC.get(s);
            if (mapped != null) {
                sb.append(mapped);
            } else if (isSafe(cp)) {
                sb.appendCodePoint(cp);
            } else {
                sb.append('_'); // spaces, punctuation, emoji, any other unicode
            }
        });
        String slug = sb.toString()
            .replaceAll("_{2,}", "_")
            .replaceAll("^[._-]+|[._-]+$", "");
        if (slug.length() > 48) slug = slug.substring(0, 48);
        if (slug.isEmpty()) slug = "track";
        return slug;
    }

    /** Appends -2, -3, … until the slug is unique among {@code used}. */
    public static String dedupe(String slug, Set<String> used) {
        String result = slug;
        int n = 2;
        while (!used.add(result)) {
            String suffix = "-" + n++;
            int max = 48 - suffix.length();
            result = slug.substring(0, Math.min(slug.length(), max)) + suffix;
        }
        return result;
    }

    private static boolean isSafe(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9') || cp == '_' || cp == '-' || cp == '.';
    }
}
