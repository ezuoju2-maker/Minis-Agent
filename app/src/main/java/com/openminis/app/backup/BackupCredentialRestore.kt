package com.openminis.app.backup

object BackupCredentialRestore {
    enum class Kind { API_KEY, OAUTH }

    enum class Slot(val kind: Kind) {
        API_KEY(Kind.API_KEY),
        MANUAL_OAUTH_TOKEN(Kind.API_KEY),
        OAUTH_TOKEN(Kind.OAUTH),
        OAUTH_EMAIL(Kind.OAUTH),
        OAUTH_GCP_PROJECT(Kind.OAUTH),
    }

    interface Store {
        fun supports(slot: Slot): Boolean
        fun read(slot: Slot): String?
        fun write(slot: Slot, value: String)
        fun oauthNeedsReauth(): Boolean
    }

    fun shouldWrite(slot: Slot, local: String?, backup: String, replaceLogin: Boolean): Boolean {
        if (local.isNullOrEmpty()) return true
        if (local == backup) return false
        return when (slot.kind) {
            Kind.API_KEY -> true
            Kind.OAUTH -> replaceLogin
        }
    }

    fun restore(backup: Map<Slot, String>, store: Store): Boolean {
        val hasOAuth = backup.keys.any { it.kind == Kind.OAUTH }
        val needsReauth = hasOAuth && store.oauthNeedsReauth()
        val replaceLogin = hasOAuth && (needsReauth || store.read(Slot.OAUTH_TOKEN).isNullOrEmpty())
        var wrote = false
        for (slot in Slot.entries) {
            val value = backup[slot] ?: continue
            if (!store.supports(slot)) continue
            if (!shouldWrite(slot, store.read(slot), value, slot.kind == Kind.OAUTH && replaceLogin)) continue
            store.write(slot, value)
            wrote = true
        }
        return wrote
    }
}
