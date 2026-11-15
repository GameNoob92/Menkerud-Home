package com.noobventure.menkerud.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairLinkTest {
    private val release = PairPolicy("https://call.noobventure.com", setOf("call.noobventure.com"), allowAnyServer = false)
    private val debug = PairPolicy("https://call.noobventure.com", setOf("call.noobventure.com"), allowAnyServer = true)
    private val key = "0123456789ABCDEFGHJK"
    private fun qr(api: String) = "menkerudhome://pair?api=" + java.net.URLEncoder.encode(api, "UTF-8") + "&code=" + key

    @Test fun `the QR text gives the server and the key`() =
        assertEquals(PairInput.Ok(PairLink("https://call.noobventure.com", key)), readPairInput(qr("https://call.noobventure.com/"), release))

    @Test fun `a key typed with dashes, spaces and small letters is passed on as typed`() =
        assertEquals(
            PairInput.Ok(PairLink("https://call.noobventure.com", "o123-4567 89ab-cdef ghjk")),
            readPairInput("  o123-4567 89ab-cdef ghjk \n", release)
        )

    @Test fun `a QR code for another server is refused in a release build`() =
        assertEquals(PairInput.ForeignServer("evil.example"), readPairInput(qr("https://evil.example"), release))

    @Test fun `plain http is refused in a release build, even for the right host`() =
        assertEquals(PairInput.ForeignServer("call.noobventure.com"), readPairInput(qr("http://call.noobventure.com"), release))

    @Test fun `a debug build follows the QR code to the dev copy`() =
        assertEquals(PairInput.Ok(PairLink("http://127.0.0.1:3100", key)), readPairInput(qr("http://127.0.0.1:3100"), debug))

    @Test fun `what is not a key is refused`() {
        for (text in listOf(
            "", "hei", key.dropLast(1), key + "X", key.replace('0', '!'),
            "menkerudhome://pair?api=https%3A%2F%2Fcall.noobventure.com", "MENKERUDHOME://pair?code=kort", qr("ikke-en-adresse")
        )) assertEquals(text, PairInput.NotAKey, readPairInput(text, release))
    }

    @Test fun `the scheme may be in any letter case and the server may be left out`() =
        assertEquals(PairInput.Ok(PairLink("https://call.noobventure.com", key)), readPairInput("MenkerudHome://pair?code=$key", release))

    // An address can be written so that it looks like the right server to a person and is another one to a program.
    @Test fun `an address that only shows the right host in front of another one is refused`() =
        assertEquals(PairInput.ForeignServer("evil.example"), readPairInput(qr("https://call.noobventure.com@evil.example/"), release))

    @Test fun `the address is rebuilt from what was understood, not passed on as written`() =
        assertEquals(
            PairInput.Ok(PairLink("https://call.noobventure.com:8443/base", key)),
            readPairInput(qr("HTTPS://Call.Noobventure.com:8443/base/?x=1#frag"), release)
        )

    @Test fun `an address with a user name in it is not a key, whatever the build`() =
        assertEquals(PairInput.NotAKey, readPairInput(qr("http://someone@127.0.0.1:3100"), debug))
}
