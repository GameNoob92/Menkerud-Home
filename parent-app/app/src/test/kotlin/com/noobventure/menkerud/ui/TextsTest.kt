package com.noobventure.menkerud.ui

import com.noobventure.menkerud.call.EndReason
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TextsTest {
    @Test fun `a missed call says when`() = assertEquals(
        "Tapt anrop hjemmefra kl. 14:32",
        missedText(Instant.parse("2026-11-15T13:32:10Z").toEpochMilli(), ZoneId.of("Europe/Oslo"))
    )

    @Test fun `a missed call early in the morning keeps its leading zeros`() = assertEquals(
        "Tapt anrop hjemmefra kl. 07:05",
        missedText(Instant.parse("2026-07-01T05:05:59Z").toEpochMilli(), ZoneId.of("Europe/Oslo"))
    )

    @Test fun `every way a call can end has its words`() {
        assertEquals("Ingen svarte hjemme", endText(EndReason.NOBODY_HOME))
        assertEquals("Opptatt hjemme", endText(EndReason.BUSY_HOME))
        assertEquals("Skjermen hjemme er ikke tilkoblet", endText(EndReason.KIOSK_OFFLINE))
        assertEquals("Samtalen er avsluttet", endText(EndReason.HUNG_UP))
        assertEquals("Samtalen er avsluttet", endText(EndReason.OTHER_SIDE_LEFT))
        assertEquals("Samtalen er over", endText(EndReason.CALL_OVER))
        assertEquals("For mange forsøk – prøv igjen om litt", endText(EndReason.TOO_MANY))
        assertEquals("Får ikke kontakt med serveren", endText(EndReason.NO_SERVER))
        assertEquals("Klarte ikke å koble til samtalen", endText(EndReason.COULD_NOT_JOIN))
        assertEquals("Telefonen er koblet fra. Koble til på nytt med en ny nøkkel.", endText(EndReason.UNPAIRED))
    }
}
