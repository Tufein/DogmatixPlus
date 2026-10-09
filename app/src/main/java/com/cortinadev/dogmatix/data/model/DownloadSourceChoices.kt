package com.cortinadev.dogmatix.data.model

/** Opaque IDs identify an indexed choice; URLs, tokens and raw source names never reach the UI. */
data class DownloadSourceOption(
    val id: String,
    val label: String,
    val current: Boolean,
    val kind: DownloadSourceKind
)

enum class DownloadSourceKind { WEB, TORRENT, ROMM }

/** A one-use snapshot. A later pause, stop, retry, removal or source change makes it stale. */
data class DownloadSourceChoices(val token: String, val fileName: String, val options: List<DownloadSourceOption>)

enum class SourceChangeResult { STARTED, STALE, UNAVAILABLE, RESTRICTED }
