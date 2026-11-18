package dev.pboard.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipEntitiesTest {
    private fun ents(text: String, region: String = "GB") = ClipEntities.extract(text, region).map { it.type to it.text }

    @Test fun phoneInSentence() {
        // ED-07: Gboard offers the number as its own chip.
        assertEquals(listOf(EntityType.PHONE to "07700 900123"), ents("Call me on 07700 900123 tomorrow"))
    }

    @Test fun nationalNumberFromAnotherRegion() {
        // A UK number copied on a US-set phone: found through the fallback regions, bare digit runs are not.
        val r = ClipEntities.extract("Call me on 07700 900123 tomorrow", listOf("US", "GB"))
        assertEquals(listOf(EntityType.PHONE to "07700 900123"), r.map { it.type to it.text })
        assertEquals(EntityType.PHONE, ClipEntities.classifyWhole("07700 900123", listOf("US", "GB")))
        assertTrue(ClipEntities.extract("Order 07700900123 shipped", listOf("US", "GB")).isEmpty())
    }

    @Test fun usPhoneFormats() {
        assertEquals(listOf(EntityType.PHONE to "(415) 555-0132"), ents("Office: (415) 555-0132.", "US"))
        assertEquals(listOf(EntityType.PHONE to "+1 415 555 0132"), ents("ring +1 415 555 0132 after 5", "GB"))
    }

    @Test fun otpCodes() {
        assertEquals(listOf(EntityType.OTP to "482913"), ents("Your verification code is 482913. Don't share it."))
        assertEquals(listOf(EntityType.OTP to "105577"), ents("G-105577 is your Google verification code."))
        assertEquals(listOf(EntityType.OTP to "7301"), ents("Use PIN 7301 to log in"))
        assertEquals(listOf(EntityType.OTP to "123 456"), ents("Your one-time passcode: 123 456"))
    }

    @Test fun digitsWithoutKeywordAreNotCodes() {
        assertTrue(ents("We sold 482913 units last year").none { it.first == EntityType.OTP })
        assertTrue(ents("The meeting is in room 4021").isEmpty())
    }

    @Test fun linksAndEmails() {
        assertEquals(
            listOf(EntityType.LINK to "https://example.com/a?b=1", EntityType.EMAIL to "jo.bloggs@example.co.uk"),
            ents("See https://example.com/a?b=1, or mail jo.bloggs@example.co.uk."),
        )
        assertEquals(listOf(EntityType.LINK to "www.pboard.dev/docs"), ents("docs at www.pboard.dev/docs!"))
        assertEquals(listOf(EntityType.LINK to "github.com/foo/bar"), ents("code: github.com/foo/bar"))
    }

    @Test fun emailIsNotAlsoALink() {
        assertEquals(listOf(EntityType.EMAIL to "a.b@example.com"), ents("write to a.b@example.com today"))
    }

    @Test fun addresses() {
        assertEquals(
            listOf(EntityType.ADDRESS to "1600 Amphitheatre Parkway, Mountain View, CA 94043"),
            ents("Meet at 1600 Amphitheatre Parkway, Mountain View, CA 94043 at noon", "US"),
        )
        assertEquals(
            listOf(EntityType.ADDRESS to "221B Baker Street, London NW1 6XE"),
            ents("Address: 221B Baker Street, London NW1 6XE"),
        )
        assertEquals(listOf(EntityType.ADDRESS to "SW1A 1AA"), ents("Buckingham Palace, London, SW1A 1AA"))
    }

    @Test fun wholeClipIsNotRepeated() {
        assertTrue(ents("07700 900123").isEmpty())
        assertTrue(ents("https://example.com").isEmpty())
    }

    @Test fun severalEntitiesInOrderCapped() {
        val r = ents("Call 07700 900123 or email a@b.io, see b.io/x, code 5555 for login, 10 Downing Street")
        assertEquals(ClipEntities.MAX, r.size)
        assertEquals(listOf(EntityType.PHONE, EntityType.EMAIL, EntityType.LINK), r.map { it.first })
    }

    @Test fun wholeClipClassified() {
        assertEquals(EntityType.PHONE, ClipEntities.classifyWhole("07700 900123", "GB"))
        assertEquals(EntityType.OTP, ClipEntities.classifyWhole("482913", "GB"))
        assertEquals(EntityType.EMAIL, ClipEntities.classifyWhole(" a.b@example.com ", "GB"))
        assertEquals(EntityType.LINK, ClipEntities.classifyWhole("https://example.com/x", "GB"))
        assertEquals(null, ClipEntities.classifyWhole("Call me on 07700 900123", "GB"))
        assertEquals(null, ClipEntities.classifyWhole("hello", "GB"))
    }

    @Test fun plainProseHasNothing() {
        assertTrue(ents("Thanks for yesterday, see you on Friday at 7.").isEmpty())
        assertTrue(ents("").isEmpty())
    }
}
