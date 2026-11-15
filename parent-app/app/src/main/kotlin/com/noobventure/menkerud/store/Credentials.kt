package com.noobventure.menkerud.store

import kotlinx.serialization.Serializable

/**
 * What a paired phone knows: the server it was paired with, its id and secret there, and which parent it is
 * (`who` = "mor" or "far", `label` = the name to show). All of it comes from the server when the key is redeemed.
 */
@Serializable
data class Credentials(val apiBase: String, val deviceId: String, val secret: String, val who: String, val label: String) {
    /** Never the secret: this is what ends up in a log line or a crash report if the object is ever printed. */
    override fun toString() = "Credentials(apiBase=$apiBase, deviceId=$deviceId, who=$who)"
}
