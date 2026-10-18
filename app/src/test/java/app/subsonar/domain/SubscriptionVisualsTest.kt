package app.subsonar.domain

import app.subsonar.data.Category
import app.subsonar.ui.components.SubscriptionIcons
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SubscriptionVisualsTest {
    private fun icon(name: String, cat: Category = Category.OTHER) = SubscriptionVisuals.defaultIcon(name, cat)

    @Test fun nameKeywordsWinOverCategory() {
        assertEquals("gym", icon("Fitnessclub", Category.OTHER))
        assertEquals("gym", icon("SATS Norge"))
        assertEquals("music", icon("Spotify Premium", Category.STREAMING))
        assertEquals("streaming", icon("Netflix"))
        assertEquals("streaming", icon("Streamflix"))
        assertEquals("streaming", icon("Strømmeflix", Category.STREAMING)) // not "strøm" = electricity
        assertEquals("bolt", icon("Strøm Fjordkraft"))
        assertEquals("book", icon("Storytel"))
        assertEquals("cloud", icon("iCloud+ 200 GB", Category.SOFTWARE))
        assertEquals("phone", icon("Telenor Mobil"))
        assertEquals("wifi", icon("Altibox Fiber"))
        assertEquals("bolt", icon("Stadtwerke Strom"))
        assertEquals("insurance", icon("Innboforsikring"))
        assertEquals("insurance", icon("Hausratversicherung"))
        assertEquals("home", icon("Husleie"))
        assertEquals("car", icon("EasyPark"))
        assertEquals("train", icon("Ruter 30-dagersbillett"))
        assertEquals("news", icon("Aftenposten"))
        assertEquals("gaming", icon("Xbox Game Pass"))
        assertEquals("education", icon("Duolingo Super"))
    }

    @Test fun shortKeywordsOnlyMatchWholeWords() {
        // "tv" must not match inside other words, "bil" not inside "mobil"... (mobil -> phone)
        assertEquals("receipt", icon("Patvarbeid"))
        assertEquals("phone", icon("Mobilabo", Category.PHONE_INTERNET))
        assertEquals("streaming", icon("Viasat TV"))
        assertEquals("car", icon("Bil leasing"))
    }

    @Test fun categoryFallback() {
        assertEquals("receipt", icon("Xyz", Category.OTHER))
        assertEquals("bolt", icon("Xyz", Category.UTILITIES))
        for (c in Category.entries) {
            assertTrue(icon("qqq", c) in SubscriptionVisuals.ICONS)
            assertTrue(SubscriptionVisuals.defaultColor(c) in SubscriptionVisuals.COLORS)
        }
    }

    @Test fun encodeParseRoundTripAndGarbage() {
        val i = Avatar.Icon("gym"); val e = Avatar.Emoji("🎬")
        assertEquals(i, SubscriptionVisuals.parse(SubscriptionVisuals.encode(i)))
        assertEquals(e, SubscriptionVisuals.parse(SubscriptionVisuals.encode(e)))
        assertNull(SubscriptionVisuals.parse(null))
        assertNull(SubscriptionVisuals.parse("icon:netflix-logo"))
        assertNull(SubscriptionVisuals.parse("emoji:   "))
        assertNull(SubscriptionVisuals.parse("weird"))
        assertNull(SubscriptionVisuals.validColor("neon"))
    }

    @Test fun resolveUsesChoicesOrDefaults() {
        val auto = SubscriptionVisuals.resolve("Netflix", Category.STREAMING, null, null)
        assertTrue(auto.isAutomatic); assertEquals(Avatar.Icon("streaming"), auto.avatar); assertEquals("lingonberry", auto.colorKey)
        val custom = SubscriptionVisuals.resolve("Netflix", Category.STREAMING, "moss", "emoji:🍿")
        assertFalse(custom.isAutomatic); assertEquals(Avatar.Emoji("🍿"), custom.avatar); assertEquals("moss", custom.colorKey)
        // An unknown stored colour falls back but keeps the chosen icon.
        val partial = SubscriptionVisuals.resolve("X", Category.MUSIC, "neon", "icon:star")
        assertEquals("heather", partial.colorKey); assertEquals(Avatar.Icon("star"), partial.avatar)
    }

    @Test fun everyIconHasADrawableAndAMapping() {
        assertEquals(31, SubscriptionVisuals.ICONS.size)
        assertEquals(SubscriptionVisuals.ICONS.size, SubscriptionVisuals.ICONS.toSet().size)
        for (k in SubscriptionVisuals.ICONS) {
            assertTrue("drawable for $k", File("src/main/res/drawable/ic_sub_$k.xml").exists())
            assertTrue("mapping for $k", SubscriptionIcons.RES.containsKey(k))
        }
        assertEquals(SubscriptionVisuals.ICONS.toSet(), SubscriptionIcons.RES.keys)
    }

    @Test fun paletteAndEmojiSets() {
        assertEquals(12, SubscriptionVisuals.COLORS.size)
        assertEquals(SubscriptionVisuals.COLORS.toSet(), app.subsonar.ui.theme.PALETTE_KEYS)
        assertEquals(SubscriptionVisuals.EMOJIS.size, SubscriptionVisuals.EMOJIS.toSet().size)
    }
}
