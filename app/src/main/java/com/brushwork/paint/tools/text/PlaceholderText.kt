package com.brushwork.paint.tools.text

/*
 * Placeholder ("fake") text for layouts: classic Lorem ipsum, plain English filler and the two
 * kinds of Japanese dummy text used in manga and design (「テキストが入ります」 sentences and the
 * あいうえお syllabary). Pure Kotlin; [PlaceholderFit] fits it into a text box.
 */

/** Kinds of placeholder text. [cjk] text breaks between any two characters (no spaces). */
enum class PlaceholderKind(val label: String, val cjk: Boolean) {
    LOREM("Lorem ipsum", false),
    ENGLISH("English", false),
    JAPANESE("テキストが入ります", true),
    KANA("あいうえお", true),
}

/** How much placeholder text to insert when not filling a box. */
enum class PlaceholderAmount(val label: String) {
    /** Exactly as much as fits the text box (needs a fixed box width / height). */
    FILL("Fill the box"),
    SHORT("Short"),
    PARAGRAPH("Paragraph"),
    THREE_PARAGRAPHS("3 paragraphs"),
}

object PlaceholderText {

    /** Cicero, de Finibus 1.10.32-33, as scrambled by typesetters since the 1500s (public domain). */
    private val LOREM_PARAGRAPHS = listOf(
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
            "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. " +
            "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. " +
            "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.",
        "Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium doloremque laudantium, totam rem aperiam, " +
            "eaque ipsa quae ab illo inventore veritatis et quasi architecto beatae vitae dicta sunt explicabo. " +
            "Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur magni dolores eos qui ratione voluptatem sequi nesciunt.",
        "At vero eos et accusamus et iusto odio dignissimos ducimus qui blanditiis praesentium voluptatum deleniti atque corrupti quos dolores et quas molestias excepturi sint occaecati cupiditate non provident. " +
            "Nam libero tempore, cum soluta nobis est eligendi optio cumque nihil impedit quo minus id quod maxime placeat facere possimus.",
    )

    private const val LOREM_SHORT = "Lorem ipsum dolor sit amet, consectetur adipiscing elit."

    private val ENGLISH_PARAGRAPHS = listOf(
        "This is placeholder text. It shows how the words will sit in the layout before the real text is written. " +
            "Look at the size of the letters, the space between the lines and how the lines break. " +
            "When the final words are ready, simply replace these sentences with them.",
        "A good layout leaves room for the text to breathe. Speech bubbles need a little space around the words, " +
            "and captions read best when their lines are neither too long nor too short. " +
            "Try a few sizes and spacings until the page feels balanced.",
        "Every story has a beginning, a middle and an end. The characters speak, the scene changes and the pages turn. " +
            "This filler text stands in for all of it for now, so the drawing can come first.",
    )

    private const val ENGLISH_SHORT = "Your text goes here."

    private val JAPANESE_SENTENCES = listOf(
        "ここにテキストが入ります。",
        "このテキストはダミーです。",
        "文字の大きさや行間を確かめるために入れています。",
        "本文が決まったら差し替えてください。",
        "吹き出しやキャプションの配置を確認できます。",
    )

    private const val JAPANESE_SHORT = "テキストが入ります"

    private const val KANA = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"

    private const val KANA_SHORT = "あいうえおかきくけこ"

    /** One paragraph of [kind] (index [n], cycling through the available ones). */
    fun paragraph(kind: PlaceholderKind, n: Int = 0): String = when (kind) {
        PlaceholderKind.LOREM -> LOREM_PARAGRAPHS[n.mod(LOREM_PARAGRAPHS.size)]
        PlaceholderKind.ENGLISH -> ENGLISH_PARAGRAPHS[n.mod(ENGLISH_PARAGRAPHS.size)]
        PlaceholderKind.JAPANESE -> (0 until 4).joinToString("") { JAPANESE_SENTENCES[(n * 4 + it).mod(JAPANESE_SENTENCES.size)] }
        PlaceholderKind.KANA -> KANA + "。" + KANA.substring(0, 20) + "。"
    }

    /** Placeholder text of [kind] and [amount] ([PlaceholderAmount.FILL] gives one paragraph: see [PlaceholderFit]). */
    fun text(kind: PlaceholderKind, amount: PlaceholderAmount): String = when (amount) {
        PlaceholderAmount.SHORT -> when (kind) {
            PlaceholderKind.LOREM -> LOREM_SHORT
            PlaceholderKind.ENGLISH -> ENGLISH_SHORT
            PlaceholderKind.JAPANESE -> JAPANESE_SHORT
            PlaceholderKind.KANA -> KANA_SHORT
        }
        PlaceholderAmount.FILL, PlaceholderAmount.PARAGRAPH -> paragraph(kind, 0)
        PlaceholderAmount.THREE_PARAGRAPHS -> (0 until 3).joinToString("\n") { paragraph(kind, it) }
    }

    /**
     * The units filling adds one at a time: words (with the space before them) for Latin text,
     * single characters for Japanese; together they read as continuous text, as long as needed
     * ([maxChars] in all).
     */
    fun tokens(kind: PlaceholderKind, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        var chars = 0
        var p = 0
        while (chars < maxChars) {
            val para = when (kind) {
                PlaceholderKind.KANA -> KANA
                PlaceholderKind.JAPANESE -> JAPANESE_SENTENCES[p.mod(JAPANESE_SENTENCES.size)]
                else -> paragraph(kind, p)
            }
            p++
            if (kind.cjk) {
                var i = 0
                while (i < para.length && chars < maxChars) {
                    val end = i + Character.charCount(para.codePointAt(i))
                    out += para.substring(i, end)
                    chars += end - i
                    i = end
                }
            } else {
                for (w in para.split(' ')) {
                    if (w.isEmpty()) continue
                    val t = if (out.isEmpty()) w else " $w"
                    if (chars + t.length > maxChars) return out
                    out += t
                    chars += t.length
                }
            }
        }
        return out
    }

    /**
     * What to put between existing text [before] and inserted placeholder text: nothing when
     * there is no text or it already ends with white space, a line break before whole
     * paragraphs, else a space (Latin) or nothing (Japanese).
     */
    fun separator(before: String, kind: PlaceholderKind, amount: PlaceholderAmount): String = when {
        before.isEmpty() || before.last().isWhitespace() -> ""
        amount == PlaceholderAmount.PARAGRAPH || amount == PlaceholderAmount.THREE_PARAGRAPHS -> "\n"
        kind.cjk -> ""
        else -> " "
    }
}
