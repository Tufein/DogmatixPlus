package com.tufein.valkyr.data.model

import com.tufein.valkyr.data.local.entity.DownloadableFileEntity

data class DownloadableFileWithTags(
    val file: DownloadableFileEntity,
    val tags: List<String>
)
