package com.noobventure.menkerud.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.noobventure.menkerud.call.CallKind
import com.noobventure.menkerud.call.LastCall
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.prefsStore: DataStore<Preferences> by preferencesDataStore(name = "prefs")

/** The little the app remembers that is no secret: the last call, for the home screen. */
class AppPrefs(context: Context) {
    private val store = context.applicationContext.prefsStore

    val lastCall: Flow<LastCall?> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }      // an unreadable file is an empty one
        .map { prefs ->
            val at = prefs[LAST_AT]
            val kind = prefs[LAST_KIND]?.let { name -> CallKind.entries.firstOrNull { it.name == name } }
            if (at != null && kind != null) LastCall(at, kind) else null
        }

    suspend fun setLastCall(call: LastCall) {
        store.edit { prefs ->
            prefs[LAST_AT] = call.at
            prefs[LAST_KIND] = call.kind.name
        }
    }

    private companion object {
        val LAST_AT = longPreferencesKey("last_call_at")
        val LAST_KIND = stringPreferencesKey("last_call_kind")
    }
}
