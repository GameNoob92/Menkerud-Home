package com.noobventure.menkerud.pairing

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** Where a key is to be redeemed, and the key as the parent gave it. */
data class PairLink(val apiBase: String, val code: String)

/** Which servers this build lets a QR code name. A typed key always goes to [defaultApi]. */
class PairPolicy(val defaultApi: String, val allowedHosts: Set<String>, val allowAnyServer: Boolean)

sealed interface PairInput {
    data class Ok(val link: PairLink) : PairInput
    /** A QR code that points at a server this build does not trust. */
    data class ForeignServer(val host: String) : PairInput
    data object NotAKey : PairInput
}

private const val QR_PREFIX = "menkerudhome://pair"
private val KEY = Regex("[0-9A-Za-z]{20}")

/** A key as the pairing page shows it: 20 letters and digits, however they are grouped. The server does the forgiving
 *  (small letters, O for 0, I and L for 1), so the key is passed on as it was typed. */
private fun isKey(text: String) = KEY.matches(text.replace(" ", "").replace("-", ""))

/** `a=1&b=2`, URL-decoded; the first of a repeated name wins. Throws IllegalArgumentException on a broken escape. */
private fun readQuery(query: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
        if (name !in out) out[name] = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
    }
    return out
}

/**
 * Reads what a parent typed or scanned on the pairing screen: either the key itself, or the text of the QR code,
 * `menkerudhome://pair?api=<address>&code=<key>`.
 *
 * The server address of a QR code is taken apart and put together again from the parts that were understood, so
 * that the program that later connects cannot read the text differently from the check made here.
 */
fun readPairInput(text: String, policy: PairPolicy): PairInput {
    val typed = text.trim()
    if (!typed.startsWith(QR_PREFIX, ignoreCase = true)) {
        return if (isKey(typed)) PairInput.Ok(PairLink(policy.defaultApi, typed)) else PairInput.NotAKey
    }
    val query = try {
        readQuery(typed.substringAfter('?', ""))
    } catch (e: IllegalArgumentException) {
        return PairInput.NotAKey
    }
    val code = query["code"]?.trim().orEmpty()
    if (!isKey(code)) return PairInput.NotAKey
    val api = query["api"] ?: return PairInput.Ok(PairLink(policy.defaultApi, code))

    val uri = try {
        URI(api.trim())
    } catch (e: URISyntaxException) {
        return PairInput.NotAKey
    }
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    if (host == null || (scheme != "http" && scheme != "https")) return PairInput.NotAKey
    val trusted = scheme == "https" && policy.allowedHosts.any { it.equals(host, ignoreCase = true) }
    if (!policy.allowAnyServer && !trusted) return PairInput.ForeignServer(host)
    if (uri.rawUserInfo != null) return PairInput.NotAKey

    val port = if (uri.port == -1) "" else ":" + uri.port
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return PairInput.Ok(PairLink("$scheme://$host$port$path", code))
}
