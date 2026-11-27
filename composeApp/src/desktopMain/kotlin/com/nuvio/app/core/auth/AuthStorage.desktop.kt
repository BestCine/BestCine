package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopStorage

internal actual object AuthStorage {
    private val store = DesktopStorage.store("nuvio_auth")

    actual fun loadAnonymousUserId(): String? =
        store.getString("anonymous_user_id")

    actual fun saveAnonymousUserId(userId: String) {
        store.putString("anonymous_user_id", userId)
    }

    actual fun clearAnonymousUserId() {
        store.remove("anonymous_user_id")
    }

    actual fun loadFlag(key: String): Boolean =
        store.getBoolean(key) == true

    actual fun saveFlag(key: String, value: Boolean) {
        store.putBoolean(key, value)
    }

    actual fun loadSupabaseSessionJson(): String? =
        store.getString("supabase_session_json")

    actual fun saveSupabaseSessionJson(sessionJson: String) {
        store.putString("supabase_session_json", sessionJson)
    }

    actual fun clearSupabaseSessionJson() {
        store.remove("supabase_session_json")
    }
}
