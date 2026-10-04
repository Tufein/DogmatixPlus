package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.FrontendCheck.Detail
import com.cortinadev.dogmatix.util.FrontendCheck.Frontend
import com.cortinadev.dogmatix.util.FrontendCheck.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class FrontendCheckTest {
    private val nothing = FrontendCheck.State(false, false, false, false, false, false)
    private fun of(s: FrontendCheck.State, f: Frontend) = FrontendCheck.evaluate(s).first { it.frontend == f }

    @Test fun `without a download folder ES-DE asks for it first`() {
        assertEquals(Detail.NEEDS_DOWNLOAD_FOLDER, of(nothing, Frontend.ES_DE).detail)
        assertEquals(Detail.NEEDS_DOWNLOAD_FOLDER, of(nothing.copy(esdeFolderSet = true), Frontend.ES_DE).detail)
    }

    @Test fun `ES-DE needs its folder and then the covers`() {
        val base = nothing.copy(downloadFolderSet = true)
        assertEquals(Detail.ESDE_NO_FOLDER, of(base, Frontend.ES_DE).detail)
        assertEquals(Detail.ESDE_NO_COVERS, of(base.copy(esdeFolderSet = true), Frontend.ES_DE).detail)
        assertEquals(Status.READY, of(base.copy(esdeFolderSet = true, esdeCovers = true), Frontend.ES_DE).status)
    }

    @Test fun `each other frontend follows its own setting`() {
        assertEquals(Status.TODO, of(nothing, Frontend.IISU).status)
        assertEquals(Status.READY, of(nothing.copy(iisuFolderSet = true), Frontend.IISU).status)
        assertEquals(Status.TODO, of(nothing, Frontend.PEGASUS).status)
        assertEquals(Status.READY, of(nothing.copy(pegasusCovers = true), Frontend.PEGASUS).status)
        assertEquals(Status.TODO, of(nothing, Frontend.RETROARCH).status)
        assertEquals(Status.READY, of(nothing.copy(retroArchThumbnailsSet = true), Frontend.RETROARCH).status)
    }

    @Test fun `Daijisho is always by hand and every frontend is listed once`() {
        assertEquals(Status.MANUAL, of(nothing, Frontend.DAIJISHO).status)
        assertEquals(Frontend.entries, FrontendCheck.evaluate(nothing).map { it.frontend })
    }
}
