package com.phoebe.app

import com.phoebe.app.domain.LocalFolderMediaSourceConfig
import com.phoebe.app.sources.LocalFolderCatalogBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

class LocalFolderCatalogBuilderDesktopTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun indexesMp3FileInFolder() = runTest {
        val root = temp.newFolder("music")
        File(root, "fixture.mp3").writeBytes(byteArrayOf(0x49, 0x44, 0x33)) // minimal ID3-ish header; jaudiotagger may still treat as audio
        val cfg = LocalFolderMediaSourceConfig(
            id = "lf-test",
            rootUri = root.toURI().toString(),
            label = "Test",
            enabled = true,
        )
        val snap = LocalFolderCatalogBuilder.build(cfg)
        assertTrue(snap.albums.isNotEmpty() || snap.tracksByParent.isNotEmpty(), "expected at least one album or track map entry")
    }

    @Test
    fun indexesAudioFilesThroughSymlinkedFolder() = runTest {
        val target = temp.newFolder("nas-music")
        File(target, "fixture.mp3").writeBytes(byteArrayOf(0x49, 0x44, 0x33))
        val link = temp.root.resolve("music-link").toPath()
        assumeTrue(
            "Symlinks are required for this regression test.",
            runCatching { Files.createSymbolicLink(link, target.toPath()) }.isSuccess,
        )
        val cfg = LocalFolderMediaSourceConfig(
            id = "lf-link",
            rootUri = link.toUri().toString(),
            label = "NAS",
            enabled = true,
        )

        val snap = LocalFolderCatalogBuilder.build(cfg)

        assertTrue(snap.tracksByParent.values.flatten().any { it.title == "fixture" })
    }
}
