package war.metaphor.util;

import lombok.experimental.UtilityClass;

import java.security.SecureRandom;
import java.util.*;


@UtilityClass
public class Dictionary {


    private static final String STRICT_CHARS   = "abcdefghijklmnopqrstuvwxyz";
    private static final String ALPHA_CHARS    = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String ILLUSION_CHARS = "IlI1lIl1";

    
    private static final char[] UNICODE_LOOKALIKES = {
        '\u0430', // а  (looks like a)
        '\u0435', // е  (looks like e)
        '\u0456', // і  (looks like i)
        '\u043E', // о  (looks like o)
        '\u0440', // р  (looks like p)
        '\u0441', // с  (looks like c)
        '\u0445', // х  (looks like x)
        '\u0443', // у  (looks like y)
        '\u0392', // Β  (looks like B)
        '\u0395', // Ε  (looks like E)
        '\u0396', // Ζ  (looks like Z)
        '\u0397', // Η  (looks like H)
        '\u0399', // Ι  (looks like I)
        '\u039A', // Κ  (looks like K)
        '\u039C', // Μ  (looks like M)
        '\u039D', // Ν  (looks like N)
        '\u039F', // Ο  (looks like O)
        '\u03A1', // Ρ  (looks like P)
        '\u03A4', // Τ  (looks like T)
        '\u03A5', // Υ  (looks like Y)
        '\u03A7', // Χ  (looks like X)
    };

    /**
     * All 86 Elder Futhark / Futhorc / Medieval rune codepoints in the
     * Runic block (U+16A0–U+16F8) that satisfy {@code Character.isLetter()}.
     * Every entry is a valid Java identifier start character.
     */
    private static final char[] RUNIC_CHARS = {
        '\u16A0', '\u16A1', '\u16A2', '\u16A3', '\u16A4', '\u16A5', '\u16A6', '\u16A7',
        '\u16A8', '\u16A9', '\u16AA', '\u16AB', '\u16AC', '\u16AD', '\u16AE', '\u16AF',
        '\u16B0', '\u16B1', '\u16B2', '\u16B3', '\u16B4', '\u16B5', '\u16B6', '\u16B7',
        '\u16B8', '\u16B9', '\u16BA', '\u16BB', '\u16BC', '\u16BD', '\u16BE', '\u16BF',
        '\u16C0', '\u16C1', '\u16C2', '\u16C3', '\u16C4', '\u16C5', '\u16C6', '\u16C7',
        '\u16C8', '\u16C9', '\u16CA', '\u16CB', '\u16CC', '\u16CD', '\u16CE', '\u16CF',
        '\u16D0', '\u16D1', '\u16D2', '\u16D3', '\u16D4', '\u16D5', '\u16D6', '\u16D7',
        '\u16D8', '\u16D9', '\u16DA', '\u16DB', '\u16DC', '\u16DD', '\u16DE', '\u16DF',
        '\u16E0', '\u16E1', '\u16E2', '\u16E3', '\u16E4', '\u16E5', '\u16E6', '\u16E7',
        '\u16E8', '\u16E9', '\u16EA', '\u16EE', '\u16EF', '\u16F0', '\u16F1', '\u16F2',
        '\u16F3', '\u16F4', '\u16F5', '\u16F6', '\u16F7', '\u16F8',
    };

    /**
     * All 27 Gothic script letters (U+10330–U+1034A) that satisfy
     * {@code Character.isLetter()}.
     *
     * <p><b>Supplementary plane — stored as int codepoints, not chars.</b>
     * Gothic lies above U+FFFF, so each glyph encodes as a UTF-16 surrogate
     * pair (two {@code char} units).  Use {@link #randomGothic(int)} which
     * calls {@code StringBuilder.appendCodePoint()} to build valid strings.
     * The output is always a legally-encoded Java {@code String} and a valid
     * JVM identifier, but its {@code length()} in chars will be {@code 2×n}
     * for {@code n} requested codepoints.
     */
    private static final int[] GOTHIC_CODEPOINTS = {
        0x10330, 0x10331, 0x10332, 0x10333, 0x10334, 0x10335, 0x10336, 0x10337,
        0x10338, 0x10339, 0x1033A, 0x1033B, 0x1033C, 0x1033D, 0x1033E, 0x1033F,
        0x10340, 0x10341, 0x10342, 0x10343, 0x10344, 0x10345, 0x10346, 0x10347,
        0x10348, 0x10349, 0x1034A,
    };

    /** Java reserved words — illegal bare identifiers, legal with $ appended. */
    private static final String[] KEYWORDS = {
        "if", "do", "for", "int", "new", "try", "var",
        "byte", "case", "char", "else", "enum", "goto", "long", "null",
        "this", "true", "void", "false", "final", "float", "short", "super",
        "break", "catch", "class", "const", "throw", "while",
        "assert", "double", "import", "native", "return", "static", "switch",
        "throws", "boolean", "default", "extends", "finally", "package",
        "private", "abstract", "continue", "interface", "protected", "public",
        "strictfp", "volatile", "instanceof", "implements", "synchronized",
        "transient"
    };

    // ── RNG ───────────────────────────────────────────────────────────────────

    private static final SecureRandom rand = new SecureRandom();

    // ── used-name registries (one per Purpose, global across the run) ─────────

    private static final Set<String> usedClass   = new HashSet<>();
    private static final Set<String> usedField   = new HashSet<>();
    private static final Set<String> usedMethod  = new HashSet<>();
    private static final Set<String> usedGeneric = new HashSet<>();

    // per-purpose counter for Mode.COUNTER
    private static final Map<Purpose, Long> counters = new EnumMap<>(Purpose.class);

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Naming modes understood by {@link #gen(int, Purpose, Mode, String)}.
     */
    public enum Mode {

        /**
         * Lowercase a–z only.  Identical to the original hard-coded behaviour.
         * Safe for all JVM identifier positions.
         */
        RANDOM,

        /**
         * Mixed-case a–z, A–Z, 0–9 (first char is always a letter so the
         * name remains a legal identifier).  Maximises entropy per character.
         */
        ALPHA,

        /**
         * Uses only the characters {@code I}, {@code l}, and {@code 1}.
         * In most monospace/programming fonts these three glyphs are
         * indistinguishable, making the output very hard to read or copy.
         */
        ILLUSION,

        /**
         * Builds identifiers from Cyrillic and Greek characters that are
         * visually identical to Latin letters in common fonts.
         * All codepoints are valid JVM identifier characters.
         * <p>Example: {@code аІоМх}, {@code рТЕNа}
         */
        UNICODE,

        /**
         * Concatenates two random Java keywords and appends {@code $} so the
         * result is a legal identifier (e.g. {@code ifdo$}, {@code trynew$}).
         * Confuses decompilers and tools that highlight keyword clashes.
         */
        KEYWORD,

        /**
         * Deterministic counter: {@code <prefix>_0}, {@code <prefix>_1}, …
         * Useful for reproducible builds or debugging.  The {@code prefix}
         * parameter is used verbatim; defaults to {@code "a"} when blank.
         */
        COUNTER,

        /**
         * Elder Futhark, Futhorc, and Medieval runes (U+16A0–U+16F8).
         * All 86 codepoints in the pool are valid Java identifier start
         * characters.  Completely unrecognisable to most Western reverse
         * engineers and unsearchable with a standard ASCII keyboard.
         * <p>Example: {@code ᚠᚢᚦᚨᚱ}, {@code ᛞᛟᛠᚲᛃ}
         */
        RUNIC,

        /**
         * CJK Unified Ideographs (U+4E00–U+9FFF, 20 991 codepoints).
         * The entire block satisfies {@code Character.isLetter()}, so every
         * generated name is a valid JVM identifier.  Renders as recognisable
         * Chinese/Japanese/Korean characters, making the output look like
         * intentional localisation rather than obfuscation.
         * <p>Example: {@code 漢字語文明}, {@code 学数理物化}
         */
        CJK,

        /**
         * Gothic script (U+10330–U+1034A, 27 letters).
         * <p><b>Supplementary plane:</b> each glyph is stored as a UTF-16
         * surrogate pair, so a name of {@code length} codepoints has
         * {@code length * 2} Java {@code char} units.  The JVM accepts
         * surrogate-pair identifiers in class files; standard decompilers
         * render them as the Gothic glyphs.  Completely exotic to any analyst
         * not specialised in early medieval scripts.
         * <p>Example: {@code 𐌰𐌱𐌲𐌳𐌴}, {@code 𐍂𐍃𐍄𐌺𐌻}
         */
        GOTHIC,

        /**
         * Maximum-entropy mode that randomly samples from <em>all four</em>
         * Unicode pools (Cyrillic/Greek lookalikes, Runic, CJK, Gothic) on
         * a per-character basis.  Each codepoint in the output is drawn from
         * a different pool at random, producing names that mix scripts and
         * are essentially unprocessable by a human analyst.
         * <p>Example: {@code ᚠ漢аᛃ𐌲字р}
         */
        CHAOS;

        /**
         * Parse a mode name case-insensitively, falling back to {@link #RANDOM}
         * for unknown values.
         */
        public static Mode of(String name) {
            if (name == null || name.isBlank()) return RANDOM;
            return switch (name.trim().toLowerCase()) {
                case "alpha"    -> ALPHA;
                case "illusion" -> ILLUSION;
                case "unicode"  -> UNICODE;
                case "keyword"  -> KEYWORD;
                case "counter"  -> COUNTER;
                case "runic"    -> RUNIC;
                case "cjk"      -> CJK;
                case "gothic"   -> GOTHIC;
                case "chaos"    -> CHAOS;
                default         -> RANDOM;
            };
        }
    }

    // ── generation ────────────────────────────────────────────────────────────

    /**
     * Backward-compatible overload — always uses {@link Mode#RANDOM} with no prefix.
     * All existing callers continue to work without change.
     */
    public String gen(int length, Purpose purpose) {
        return gen(length, purpose, Mode.RANDOM, "");
    }

    /**
     * Generate a unique identifier for the given {@code purpose} using the
     * specified {@code mode}.
     *
     * @param length  base length hint in <em>codepoints</em> (exact for all
     *                modes except KEYWORD and COUNTER which ignore it; Gothic
     *                names will have {@code 2×length} UTF-16 chars)
     * @param purpose namespace bucket — prevents collisions between classes,
     *                methods, fields, and generic names; {@code null} skips
     *                global tracking (caller manages uniqueness)
     * @param mode    naming strategy
     * @param prefix  string prepended verbatim before the generated segment
     *                (empty string = no prefix)
     */
    public String gen(int length, Purpose purpose, Mode mode, String prefix) {
        String candidate;
        Set<String> used = usedSetFor(purpose);

        do {
            candidate = prefix + generate(length, purpose, mode, prefix);
        } while (purpose != null && used.contains(candidate));

        if (purpose != null) {
            used.add(candidate);
        }

        return candidate;
    }

    public void addUsed(String s, Purpose purpose) {
        usedSetFor(purpose).add(s);
    }

    // ── private dispatch ──────────────────────────────────────────────────────

    private String generate(int length, Purpose purpose, Mode mode, String prefix) {
        return switch (mode) {
            case RANDOM    -> randomFrom(STRICT_CHARS,   Math.max(1, length));
            case ALPHA     -> randomAlpha(Math.max(1, length));
            case ILLUSION  -> randomFrom(ILLUSION_CHARS, Math.max(4, length));
            case UNICODE   -> randomFromChars(UNICODE_LOOKALIKES, Math.max(1, length));
            case KEYWORD   -> keywordName();
            case COUNTER   -> counterName(purpose, prefix);
            case RUNIC     -> randomFromChars(RUNIC_CHARS,    Math.max(1, length));
            case CJK       -> randomCJK(Math.max(1, length));
            case GOTHIC    -> randomGothic(Math.max(1, length));
            case CHAOS     -> randomChaos(Math.max(1, length));
        };
    }

    // ── generators ────────────────────────────────────────────────────────────

    /** Pick {@code len} random chars from a {@code String} charset. */
    private String randomFrom(String charset, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(charset.charAt(rand.nextInt(charset.length())));
        }
        return sb.toString();
    }

    /** Pick {@code len} random chars from a {@code char[]} pool. */
    private String randomFromChars(char[] pool, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(pool[rand.nextInt(pool.length)]);
        }
        return sb.toString();
    }

    /** Mixed-case alpha + digits; first char is always a letter. */
    private String randomAlpha(int len) {
        StringBuilder sb = new StringBuilder(len);
        String letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        sb.append(letters.charAt(rand.nextInt(letters.length())));
        for (int i = 1; i < len; i++) {
            sb.append(ALPHA_CHARS.charAt(rand.nextInt(ALPHA_CHARS.length())));
        }
        return sb.toString();
    }
    private String randomCJK(int len) {
        // U+4E00 = 19968, U+9FFF = 40959 → 20991 chars in range
        final int CJK_START = 0x4E00;
        final int CJK_RANGE = 0x9FFF - 0x4E00 + 1; // 20992
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (CJK_START + rand.nextInt(CJK_RANGE)));
        }
        return sb.toString();
    }
    private String randomGothic(int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.appendCodePoint(GOTHIC_CODEPOINTS[rand.nextInt(GOTHIC_CODEPOINTS.length)]);
        }
        return sb.toString();
    }
    private String randomChaos(int len) {
        // Pre-size assuming ~50 % Gothic (2 chars each) + 50 % BMP (1 char each)
        StringBuilder sb = new StringBuilder((int) (len * 1.5));
        for (int i = 0; i < len; i++) {
            switch (rand.nextInt(4)) {
                case 0 -> sb.append(UNICODE_LOOKALIKES[rand.nextInt(UNICODE_LOOKALIKES.length)]);
                case 1 -> sb.append(RUNIC_CHARS[rand.nextInt(RUNIC_CHARS.length)]);
                case 2 -> {
                    final int CJK_START = 0x4E00;
                    final int CJK_RANGE = 0x9FFF - 0x4E00 + 1;
                    sb.append((char) (CJK_START + rand.nextInt(CJK_RANGE)));
                }
                case 3 -> sb.appendCodePoint(GOTHIC_CODEPOINTS[rand.nextInt(GOTHIC_CODEPOINTS.length)]);
            }
        }
        return sb.toString();
    }

    private String keywordName() {
        String a = KEYWORDS[rand.nextInt(KEYWORDS.length)];
        String b = KEYWORDS[rand.nextInt(KEYWORDS.length)];
        return a + b + "$";
    }

    private String counterName(Purpose purpose, String prefix) {
        long n = counters.merge(purpose, 0L, (old, ignored) -> old + 1) - 1;
        String p = (prefix == null || prefix.isBlank()) ? "a" : prefix;
        return p + "_" + n;
    }

    private Set<String> usedSetFor(Purpose purpose) {
        if (purpose == null) return new HashSet<>();
        return switch (purpose) {
            case CLASS   -> usedClass;
            case FIELD   -> usedField;
            case METHOD  -> usedMethod;
            case GENERIC -> usedGeneric;
        };
    }
}
