package com.noobventure.menkerud.store

import java.io.File
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
class CredentialStore(private val file: File, private val box: SecretBox) {
    private val json = Json { ignoreUnknownKeys = true }
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
        state.value = credentials
    }

    fun clear() {
        file.delete()
        state.value = null
    }

    private fun read(): Credentials? {
        if (!file.exists()) return null
        val credentials = try {
            box.open(file.readBytes())?.let { json.decodeFromString(Credentials.serializer(), it.toString(Charsets.UTF_8)) }
        } catch (e: Exception) {
            null
        }
        if (credentials == null) file.delete()
        return credentials
    }
}
