package com.brushwork.paint.tools.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Placeholder text generation (pure JVM). */
class PlaceholderTextTest {

    @Test
    fun fixedAmountsOfEachKind() {
        assertEquals("Lorem ipsum dolor sit amet, consectetur adipiscing elit.", PlaceholderText.text(PlaceholderKind.LOREM, PlaceholderAmount.SHORT))
        val para = PlaceholderText.text(PlaceholderKind.LOREM, PlaceholderAmount.PARAGRAPH)
        assertTrue(para.startsWith("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod"))
        assertTrue(para.endsWith("id est laborum."))
        assertTrue(para.split(' ').size > 60)
        val three = PlaceholderText.text(PlaceholderKind.LOREM, PlaceholderAmount.THREE_PARAGRAPHS)
        assertEquals(3, three.split('\n').size)
        assertTrue(three.split('\n')[1].startsWith("Sed ut perspiciatis"))
        for (kind in PlaceholderKind.entries) {
            for (amount in PlaceholderAmount.entries) {
                val t = PlaceholderText.text(kind, amount)
                assertTrue("$kind $amount", t.isNotBlank() && t == t.trim())
            }
            assertEquals(3, PlaceholderText.text(kind, PlaceholderAmount.THREE_PARAGRAPHS).split('\n').size)
        }
        assertEquals("テキストが入ります", PlaceholderText.text(PlaceholderKind.JAPANESE, PlaceholderAmount.SHORT))
        assertTrue(PlaceholderText.text(PlaceholderKind.JAPANESE, PlaceholderAmount.PARAGRAPH).startsWith("ここにテキストが入ります。"))
        assertTrue(PlaceholderText.text(PlaceholderKind.KANA, PlaceholderAmount.PARAGRAPH).startsWith("あいうえおかきくけこ"))
        assertTrue(PlaceholderText.text(PlaceholderKind.ENGLISH, PlaceholderAmount.PARAGRAPH).startsWith("This is placeholder text."))
    }

    @Test
    fun fillTokensAreWordsOrCharactersAndReadAsOneText() {
        val latin = PlaceholderText.tokens(PlaceholderKind.LOREM, 2000)
        assertEquals("Lorem", latin[0])
        assertEquals(" ipsum", latin[1])
        assertTrue(latin.drop(1).all { it.startsWith(" ") && it.length > 1 && !it.substring(1).contains(' ') })
        val joined = latin.joinToString("")
        assertTrue(joined.length <= 2000 && joined.length > 1900)
        assertTrue("runs on past the first paragraph", joined.contains("laborum. Sed ut perspiciatis"))
        val jp = PlaceholderText.tokens(PlaceholderKind.JAPANESE, 100)
        assertEquals(100, jp.size)
        assertTrue(jp.all { it.length == 1 })
        assertTrue(jp.joinToString("").startsWith("ここにテキストが入ります。このテキストは"))
        val kana = PlaceholderText.tokens(PlaceholderKind.KANA, 60)
        assertEquals("あ", kana[0])
        assertEquals(60, kana.size)
        assertTrue(PlaceholderText.tokens(PlaceholderKind.ENGLISH, 0).isEmpty())
    }

    @Test
    fun separatorsBetweenExistingTextAndPlaceholder() {
        assertEquals("", PlaceholderText.separator("", PlaceholderKind.LOREM, PlaceholderAmount.SHORT))
        assertEquals("", PlaceholderText.separator("Title\n", PlaceholderKind.LOREM, PlaceholderAmount.PARAGRAPH))
        assertEquals(" ", PlaceholderText.separator("Title", PlaceholderKind.LOREM, PlaceholderAmount.SHORT))
        assertEquals(" ", PlaceholderText.separator("Title", PlaceholderKind.ENGLISH, PlaceholderAmount.FILL))
        assertEquals("\n", PlaceholderText.separator("Title", PlaceholderKind.LOREM, PlaceholderAmount.PARAGRAPH))
        assertEquals("\n", PlaceholderText.separator("見出し", PlaceholderKind.JAPANESE, PlaceholderAmount.THREE_PARAGRAPHS))
        assertEquals("", PlaceholderText.separator("見出し", PlaceholderKind.JAPANESE, PlaceholderAmount.FILL))
    }
}
