package com.wadio.app

import com.wadio.app.importer.ImportCoordinator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun coord(): ImportCoordinator {
        val base = tmp.newFolder("vault")
        return ImportCoordinator(
            baseDir = base,
            stagingDir = base.resolve(".staging").apply { mkdirs() },
            mediaDir = base.resolve("media").apply { mkdirs() },
            pendingDir = base.resolve(".pending").apply { mkdirs() },
        )
    }

    @Test
    fun safe_relative_media_path_rejects_embedded_nul() {
        val c = coord()
        assertFalse(c.isSafeRelativeMediaPath("media/default/bad\u0000name.jpg"))
        assertTrue(c.isSafeRelativeMediaPath("media/default/good.jpg"))
        assertFalse(c.isSafeRelativeMediaPath("media/default/../escape.jpg"))
    }

    @Test
    fun safe_record_id_rejects_separators() {
        val c = coord()
        for (bad in listOf("", ".", "..", "../escape", "sub/path", "sub\\path", "bad\u0000id")) {
            assertFalse("accepted unsafe id: $bad", c.isSafeRecordId(bad))
        }
        assertTrue(c.isSafeRecordId("record-id_123"))
    }
}
