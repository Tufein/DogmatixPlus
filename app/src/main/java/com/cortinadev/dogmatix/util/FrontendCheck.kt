package com.cortinadev.dogmatix.util

/**
 * One look at how Dogmatix is hooked up to each game frontend, from the settings alone (it does not
 * open the frontends' own folders): what is ready, what still has to be done and what only works by
 * hand. Pure JVM for the tests.
 */
object FrontendCheck {
    enum class Frontend { ES_DE, IISU, DAIJISHO, PEGASUS, RETROARCH }

    enum class Status { READY, TODO, MANUAL }

    /** Which sentence to show; the screen maps each to a string. */
    enum class Detail {
        ESDE_READY, ESDE_NO_FOLDER, ESDE_NO_COVERS,
        IISU_READY, IISU_NO_FOLDER,
        DAIJISHO_MANUAL,
        PEGASUS_READY, PEGASUS_OFF,
        RETROARCH_READY, RETROARCH_NO_FOLDER,
        NEEDS_DOWNLOAD_FOLDER
    }

    data class State(
        val downloadFolderSet: Boolean,
        val esdeFolderSet: Boolean,
        val esdeCovers: Boolean,
        val iisuFolderSet: Boolean,
        val pegasusCovers: Boolean,
        val retroArchThumbnailsSet: Boolean
    )

    data class Finding(val frontend: Frontend, val status: Status, val detail: Detail)

    fun evaluate(s: State): List<Finding> = listOf(
        // The shortcuts and the covers of every frontend are written into the download folders.
        when {
            !s.downloadFolderSet -> Finding(Frontend.ES_DE, Status.TODO, Detail.NEEDS_DOWNLOAD_FOLDER)
            !s.esdeFolderSet -> Finding(Frontend.ES_DE, Status.TODO, Detail.ESDE_NO_FOLDER)
            !s.esdeCovers -> Finding(Frontend.ES_DE, Status.TODO, Detail.ESDE_NO_COVERS)
            else -> Finding(Frontend.ES_DE, Status.READY, Detail.ESDE_READY)
        },
        if (s.iisuFolderSet) Finding(Frontend.IISU, Status.READY, Detail.IISU_READY)
        else Finding(Frontend.IISU, Status.TODO, Detail.IISU_NO_FOLDER),
        Finding(Frontend.DAIJISHO, Status.MANUAL, Detail.DAIJISHO_MANUAL),
        if (s.pegasusCovers) Finding(Frontend.PEGASUS, Status.READY, Detail.PEGASUS_READY)
        else Finding(Frontend.PEGASUS, Status.TODO, Detail.PEGASUS_OFF),
        if (s.retroArchThumbnailsSet) Finding(Frontend.RETROARCH, Status.READY, Detail.RETROARCH_READY)
        else Finding(Frontend.RETROARCH, Status.TODO, Detail.RETROARCH_NO_FOLDER)
    )
}
