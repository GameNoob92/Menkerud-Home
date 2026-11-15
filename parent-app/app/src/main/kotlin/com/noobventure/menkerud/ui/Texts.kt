package com.noobventure.menkerud.ui

import com.noobventure.menkerud.call.EndReason
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every text of the app except its name (res/values/strings.xml, for the launcher). The app has one language,
 * Norwegian bokmål, so the texts are plain Kotlin: they can be read in one place and tested without a phone.
 */
object Texts {
    // Fixed by the design (docs/superpowers/specs/2026-11-14-parent-app-design.md, §7)
    const val ringsFromHome = "Det ringer hjemmefra"
    const val answer = "Svar"
    const val notNow = "Ikke nå"
    const val ringHome = "Ring hjem"
    const val ringingHome = "Ringer hjem…"
    const val nobodyHome = "Ingen svarte hjemme"
    const val busyHome = "Opptatt hjemme"
    const val kioskOffline = "Skjermen hjemme er ikke tilkoblet"
    const val hangUp = "Legg på"
    const val missedPrefix = "Tapt anrop hjemmefra kl. "

    // Pairing
    const val pairTitle = "Koble telefonen til hjemme"
    const val pairHint = "Skriv inn nøkkelen fra siden hjemme, eller skann QR-koden."
    const val pairScan = "Skann QR-kode"
    const val pairGo = "Koble til"
    const val pairWrong = "Feil nøkkel. Sjekk den, eller lag en ny."
    const val pairExpired = "Nøkkelen er utløpt. Lag en ny."
    const val pairForeign = "Denne koden hører ikke til Menkerud Home."
    const val pairNotKey = "Dette ser ikke ut som en nøkkel."
    const val scanFailed = "Fikk ikke skannet – skriv inn nøkkelen"
    const val unpaired = "Telefonen er koblet fra. Koble til på nytt med en ny nøkkel."

    // The home screen
    const val homeAs = "Denne telefonen er %s"
    const val homeReady = "Klar – telefonen ringer når de ringer hjemmefra"
    const val homeNotReady = "Telefonen er ikke klar til å ringe ennå"
    const val homeLast = "Siste anrop: %s"

    // A call
    const val home = "Hjemme"
    const val callOver = "Samtalen er over"
    const val callEnded = "Samtalen er avsluttet"
    const val noServer = "Får ikke kontakt med serveren"
    const val couldNotJoin = "Klarte ikke å koble til samtalen"
    const val tryLater = "For mange forsøk – prøv igjen om litt"
    const val needCamMic = "Appen trenger kamera og mikrofon for å ringe"
    const val openSettings = "Åpne innstillinger"
    const val cancel = "Avbryt"
    const val connecting = "Kobler til…"
    const val micOff = "Lyd av"
    const val micOn = "Lyd på"
    const val camOff = "Kamera av"
    const val camOn = "Kamera på"
    const val camFlip = "Bytt kamera"
    const val turnPhone = "Snu telefonen for større bilde"
    const val ongoingTitle = "Samtale med hjemme"

    // Notification channels, as Android's settings show them
    const val channelCalls = "Anrop hjemmefra"
    const val channelMissed = "Tapte anrop"
    const val channelOngoing = "Pågående samtale"

    // What the phone needs before it can ring
    const val setupNotifications = "Varsler"
    const val setupFullScreen = "Vis anrop over låseskjermen"
    const val setupCamMic = "Kamera og mikrofon"
    const val setupBattery = "Ikke la batterisparing stoppe appen"
    const val setupUnusedPause = "Ikke sett appen på pause når den ikke brukes"
    const val setupPushMissing = "Push er ikke satt opp i denne versjonen"
}

/** The last word about a call. */
fun endText(reason: EndReason): String = when (reason) {
    EndReason.HUNG_UP, EndReason.OTHER_SIDE_LEFT -> Texts.callEnded
    EndReason.CALL_OVER -> Texts.callOver
    EndReason.NOBODY_HOME -> Texts.nobodyHome
    EndReason.BUSY_HOME -> Texts.busyHome
    EndReason.KIOSK_OFFLINE -> Texts.kioskOffline
    EndReason.TOO_MANY -> Texts.tryLater
    EndReason.NO_SERVER -> Texts.noServer
    EndReason.COULD_NOT_JOIN -> Texts.couldNotJoin
    EndReason.UNPAIRED -> Texts.unpaired
}

private val clock = DateTimeFormatter.ofPattern("HH:mm")

/** «Tapt anrop hjemmefra kl. 14:32». `at`: ms since 1970. */
fun missedText(at: Long, zone: ZoneId): String = Texts.missedPrefix + clock.format(Instant.ofEpochMilli(at).atZone(zone))
