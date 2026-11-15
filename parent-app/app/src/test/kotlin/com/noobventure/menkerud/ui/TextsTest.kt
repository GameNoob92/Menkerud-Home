package com.noobventure.menkerud.ui

import com.noobventure.menkerud.call.CallKind
import com.noobventure.menkerud.call.EndReason
import com.noobventure.menkerud.call.LastCall
import com.noobventure.menkerud.setup.SetupItem
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

    @Test fun `the last call in plain words`() {
        val oslo = ZoneId.of("Europe/Oslo")
        fun at(s: String) = Instant.parse(s).toEpochMilli()
        val now = at("2026-11-15T19:00:00Z")
        assertEquals("i dag kl. 14:32 – tapt anrop", lastCallText(LastCall(at("2026-11-15T13:32:00Z"), CallKind.FROM_HOME_MISSED), now, oslo))
        assertEquals("i går kl. 09:05 – de ringte, du svarte", lastCallText(LastCall(at("2026-11-14T08:05:00Z"), CallKind.FROM_HOME_ANSWERED), now, oslo))
        assertEquals("03.11. kl. 18:20 – du ringte hjem, ingen svarte", lastCallText(LastCall(at("2026-11-03T17:20:00Z"), CallKind.TO_HOME_UNANSWERED), now, oslo))
        assertEquals("i dag kl. 20:00 – du ringte hjem", lastCallText(LastCall(now, CallKind.TO_HOME_ANSWERED), now, oslo))
    }

    @Test fun `yesterday is the day before, however few hours ago`() {
        val oslo = ZoneId.of("Europe/Oslo")
        fun at(s: String) = Instant.parse(s).toEpochMilli()
        // 23:50 the evening before, seen at 00:10: twenty minutes ago, and still «i går»
        assertEquals("i går kl. 23:50 – tapt anrop", lastCallText(LastCall(at("2026-11-14T22:50:00Z"), CallKind.FROM_HOME_MISSED), at("2026-11-14T23:10:00Z"), oslo))
    }

    @Test fun `every missing piece has its words`() {
        assertEquals("Push er ikke satt opp i denne versjonen", setupText(SetupItem.PUSH_MISSING))
        assertEquals("Varsler", setupText(SetupItem.NOTIFICATIONS))
        assertEquals("Vis anrop over låseskjermen", setupText(SetupItem.FULL_SCREEN))
        assertEquals("Kamera og mikrofon", setupText(SetupItem.CAMERA_MIC))
        assertEquals("Ikke la batterisparing stoppe appen", setupText(SetupItem.BATTERY))
        assertEquals("Ikke sett appen på pause når den ikke brukes", setupText(SetupItem.UNUSED_PAUSE))
    }

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
