package com.cortinadev.dogmatix.util

object ScrapingConstants {
    const val CONNECTION_TIMEOUT_MS = 30000L
    const val MAX_RETRIES = 3
    const val RETRY_DELAY_MS = 2000L
    /** Pause before each directory request: politeness towards the server, kept short. */
    const val REQUEST_DELAY_MS = 200L
    /** Sources of each kind scanned at the same time (see DatabaseScrapingService). */
    const val PARALLEL_HTTP = 4
    const val PARALLEL_TORRENTS = 3
    const val PARALLEL_ROMM = 2
    /** Directory listings fetched from one host at the same time. */
    const val PARALLEL_PER_HOST = 2
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    
    const val TABLE_SELECTOR = "table"
    const val LINK_CELL_SELECTOR = "td:first-child a"
    const val SIZE_CELL_SELECTOR = "td:nth-child(2)"
    
    const val PARENT_DIRECTORY = ".."
    const val CURRENT_DIRECTORY = "."
    const val UNKNOWN_FILE_SIZE = "?"
    const val DEFAULT_FILE_SIZE = "0B"
}
