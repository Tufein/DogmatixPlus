package com.cortinadev.dogmatix.util

/** Only public reason codes influence guidance; no server messages or account details are displayed. */
enum class ActionHelp { NETWORK, SPACE, PERMISSION, SOURCE, AUTH, EXTRACT, VERIFY, MOVE, SYNC, BACKUP, GENERAL;
    companion object {
        fun of(entry: ActionEntry): ActionHelp? {
            if (entry.kind !in setOf(ActionKind.DOWNLOAD_FAILED, ActionKind.MOVE_FAILED, ActionKind.SYNC_FAILED, ActionKind.BACKUP_FAILED)) return null
            return when (entry.reason?.removePrefix(ActionReason.DOWNLOAD_PREFIX)) {
                "NETWORK", "TIMEOUT", "HTTP_RATE_LIMITED", "HTTP_SERVER" -> NETWORK
                "STORAGE_FULL" -> SPACE
                "STORAGE_PERMISSION", "STORAGE_WRITE" -> PERMISSION
                "HTTP_NOT_FOUND", "HTTP_OTHER", "TORRENT" -> SOURCE
                "HTTP_AUTHENTICATION" -> AUTH
                "EXTRACTION" -> EXTRACT
                "VERIFICATION" -> VERIFY
                else -> when (entry.kind) {
                    ActionKind.MOVE_FAILED -> MOVE
                    ActionKind.SYNC_FAILED -> SYNC
                    ActionKind.BACKUP_FAILED -> BACKUP
                    else -> GENERAL
                }
            }
        }
    }
}
