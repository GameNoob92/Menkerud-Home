package com.noobventure.menkerud.store

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The phone's pairing, kept sealed in one file. `current` is null when the phone is not paired – also when the file
 * is there but can no longer be opened (restored from a backup, damaged, the keystore key gone): such a file is
 * deleted, and the phone has to be paired again.
 */
class CredentialStore(
    private val file: File,
    private val box: SecretBox,
    private val pause: (Long) -> Unit = { Thread.sleep(it) }       // between tries, when the keystore does not answer
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** A pairing was found when the app started, and could not be read: the phone was paired and no longer is. */
    var unreadable = false
        private set

    /**
     * The pairing file is there, but the keystore would not say whether it can be opened. The phone is «not paired»
     * for now and the file is kept: [retry], or the next start of the app, may well read it.
     */
    var unavailable = false
        private set

    private val state = MutableStateFlow(read())

    val current: StateFlow<Credentials?> = state.asStateFlow()

    fun save(credentials: Credentials) {
        val sealed = box.seal(json.encodeToString(Credentials.serializer(), credentials).toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        // Written beside the file and moved over it, so a crash half-way never leaves half a file.
        val beside = File(file.path + ".new")
        beside.writeBytes(sealed)
        try {
            Files.move(beside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(beside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        unavailable = false
        state.value = credentials
    }

    fun clear() {
        file.delete()
        unavailable = false
        state.value = null
    }

    /** Reads the pairing once more, if the keystore did not answer the last time. */
    fun retry() {
        if (unavailable) state.value = read()
    }

    private fun read(): Credentials? {
        unavailable = false
        if (!file.exists()) return null
        val sealed = try {
            file.readBytes()
        } catch (e: IOException) {
            unavailable = true
            return null
        }
        for (wait in TRIES_AFTER_MS) {
            if (wait > 0) pause(wait)
            val plain = try {
                box.open(sealed)
            } catch (e: SecretBoxUnavailable) {
                continue                                // no answer is not «no»: ask again
            }
            val credentials = plain?.let(::decode)
            if (credentials == null) {                  // it cannot be opened, or is not a pairing: it never will be
                unreadable = true
                file.delete()
            }
            return credentials
        }
        unavailable = true
        return null
    }

    private fun decode(plain: ByteArray): Credentials? = try {
        json.decodeFromString(Credentials.serializer(), plain.toString(Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {             // SerializationException is one
        null
    }

    private companion object {
        /** The read happens on the main thread when the app starts: three tries, and under half a second in all. */
        val TRIES_AFTER_MS = listOf(0L, 100L, 300L)
    }
}
