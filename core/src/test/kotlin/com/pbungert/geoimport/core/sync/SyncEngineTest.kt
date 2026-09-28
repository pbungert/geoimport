package com.pbungert.geoimport.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Drive, as far as the engine can tell: a flat folder, oldest file first. */
    private class FakeRemote : RemoteStore {
        val files = mutableListOf<Pair<RemoteFile, ByteArray>>()
        val failDownloads = mutableSetOf<String>()
        private var next = 1

        fun put(name: String, content: String): RemoteFile {
            val file = RemoteFile("r${next++}", name, md5Hex(content.toByteArray()))
            files += file to content.toByteArray()
            return file
        }

        fun content(name: String) = files.single { it.first.name == name }.second.decodeToString()
        fun names() = files.map { it.first.name }.sorted()

        override fun list() = files.map { it.first }
        override fun download(id: String): ByteArray {
            if (id in failDownloads) throw IOException("offline")
            return files.single { it.first.id == id }.second
        }
        override fun create(name: String, content: ByteArray) = put(name, content.decodeToString())
        override fun update(id: String, content: ByteArray): RemoteFile = replace(id) {
            it.first.copy(md5 = md5Hex(content)) to content
        }
        override fun rename(id: String, name: String): RemoteFile = replace(id) {
            it.first.copy(name = name) to it.second
        }

        private fun replace(id: String, change: (Pair<RemoteFile, ByteArray>) -> Pair<RemoteFile, ByteArray>): RemoteFile {
            val i = files.indexOfFirst { it.first.id == id }
            files[i] = change(files[i])
            return files[i].first
        }
    }

    /** One device: its track folder and what its last sync remembered. */
    private inner class Device(name: String, private val remote: FakeRemote) {
        val dir: File = tmp.newFolder(name)
        val index = SyncIndex()
        private val engine = SyncEngine(
            DirectoryStore(dir, { it.endsWith(".gpx") }),
            remote,
            ConflictPolicy.KeepBoth,
            accepts = { it.endsWith(".gpx") },
        )

        fun record(file: String, content: String) = File(dir, file).writeText(content)
        fun read(file: String) = File(dir, file).readText()
        fun files() = dir.list()!!.sorted()
        fun sync(skip: Set<String> = emptySet()) = engine.sync(index, skip)
    }

    private val drive = FakeRemote()
    private val phone by lazy { Device("phone", drive) }
    private val tablet by lazy { Device("tablet", drive) }

    @Test
    fun aTrackRecordedOnThePhoneReachesTheTablet() {
        phone.record("a.gpx", "phone a")
        assertEquals(listOf("a.gpx"), phone.sync().uploaded)

        val report = tablet.sync()

        assertEquals(listOf("a.gpx"), report.downloaded)
        assertEquals("phone a", tablet.read("a.gpx"))
    }

    @Test
    fun aSecondSyncWithNothingNewDoesNothing() {
        phone.record("a.gpx", "phone a")
        phone.sync()
        tablet.sync()

        assertEquals(SyncEngine.Report(), phone.sync())
        assertEquals(SyncEngine.Report(), tablet.sync())
    }

    @Test
    fun aTrackThatGrewReplacesTheOlderCopyEverywhere() {
        phone.record("a.gpx", "phone a")
        phone.sync()
        tablet.sync()

        phone.record("a.gpx", "phone a, resumed")
        assertEquals(listOf("a.gpx"), phone.sync().uploaded)
        tablet.sync()

        assertEquals("phone a, resumed", tablet.read("a.gpx"))
        assertEquals(listOf("a.gpx"), drive.names())
    }

    @Test
    fun whenBothSidesChangedAFileTheLocalVersionMovesAside() {
        phone.record("a.gpx", "a")
        phone.sync()
        tablet.sync()
        phone.record("a.gpx", "a, phone")
        tablet.record("a.gpx", "a, tablet")
        phone.sync()

        val report = tablet.sync()

        assertEquals(listOf("a.gpx" to "a (2).gpx"), report.renamed)
        assertEquals("a, phone", tablet.read("a.gpx"))
        assertEquals("a, tablet", tablet.read("a (2).gpx"))
        phone.sync()
        assertEquals("a, tablet", phone.read("a (2).gpx"))
    }

    @Test
    fun twoDevicesRecordingUnderTheSameNameKeepBothRecordings() {
        phone.record("2026-09-27_01.gpx", "phone")
        tablet.record("2026-09-27_01.gpx", "tablet")
        phone.sync()
        tablet.sync()
        phone.sync()

        for (device in listOf(phone, tablet)) {
            assertEquals(listOf("2026-09-27_01 (2).gpx", "2026-09-27_01.gpx"), device.files())
            assertEquals("phone", device.read("2026-09-27_01.gpx"))
            assertEquals("tablet", device.read("2026-09-27_01 (2).gpx"))
        }
    }

    @Test
    fun anUploadThatLostTheRaceForItsNameTakesTheLocalFileAlong() {
        // Both devices uploaded b.gpx before either saw the other's.
        drive.put("b.gpx", "phone b")
        val mine = drive.put("b.gpx", "tablet b")
        tablet.record("b.gpx", "tablet b")
        tablet.index.synced["b.gpx"] = SyncIndex.Entry(mine.id, mine.md5)

        tablet.sync()

        assertEquals(listOf("b (2).gpx", "b.gpx"), drive.names())
        assertEquals("tablet b", tablet.read("b (2).gpx"))
        assertEquals("phone b", tablet.read("b.gpx"))
        assertEquals(mine.id, tablet.index.synced["b (2).gpx"]!!.remoteId)
        assertEquals(SyncEngine.Report(), tablet.sync())
    }

    @Test
    fun aTrackDeletedOnTheTabletStaysDeletedThereAndNowhereElse() {
        phone.record("a.gpx", "phone a")
        phone.sync()
        tablet.sync()
        File(tablet.dir, "a.gpx").delete()

        tablet.sync()
        tablet.sync()

        assertFalse(File(tablet.dir, "a.gpx").exists())
        assertTrue("a.gpx" in tablet.index.deleted)
        assertEquals(listOf("a.gpx"), drive.names())
        assertEquals("phone a", phone.read("a.gpx"))
    }

    @Test
    fun aNewTrackUnderADeletedNameIsKeptApartFromIt() {
        phone.record("a.gpx", "phone a")
        phone.sync()
        tablet.sync()
        File(tablet.dir, "a.gpx").delete()
        tablet.sync()

        tablet.record("a.gpx", "tablet a")
        tablet.sync()

        assertEquals(listOf("a (2).gpx"), tablet.files())
        assertEquals("tablet a", drive.content("a (2).gpx"))
        assertEquals("phone a", drive.content("a.gpx"))
    }

    @Test
    fun aTrackDeletedFromDriveIsUploadedAgain() {
        phone.record("a.gpx", "phone a")
        phone.sync()
        drive.files.clear()

        phone.sync()

        assertEquals("phone a", drive.content("a.gpx"))
    }

    @Test
    fun theTrackBeingRecordedIsLeftAlone() {
        phone.record("live.gpx", "half")
        phone.record("done.gpx", "whole")

        val report = phone.sync(skip = setOf("live.gpx"))

        assertEquals(listOf("done.gpx"), report.uploaded)
        assertEquals(listOf("done.gpx"), drive.names())
    }

    @Test
    fun filesThatAreNotTracksDoNotTakePart() {
        drive.put("photo.jpg", "jpeg")
        phone.record("notes.txt", "text")

        assertEquals(SyncEngine.Report(), phone.sync())
        assertEquals(listOf("photo.jpg"), drive.names())
    }

    @Test
    fun aFailedDownloadDoesNotStopTheRest() {
        val broken = drive.put("a.gpx", "a")
        drive.put("b.gpx", "b")
        drive.failDownloads += broken.id

        val report = tablet.sync()

        assertEquals(listOf("b.gpx"), report.downloaded)
        assertEquals(1, report.failures.size)
        drive.failDownloads.clear()
        assertEquals(listOf("a.gpx"), tablet.sync().downloaded)
    }

    @Test
    fun aHalfWrittenDownloadIsNeverListed() {
        File(phone.dir, ".a.gpx.part").writeText("partial")

        assertEquals(SyncEngine.Report(), phone.sync())
    }

    @Test
    fun mergeCombinesBothVersions() {
        val dir = tmp.newFolder("state")
        val engine = SyncEngine(
            DirectoryStore(dir, { true }),
            drive,
            ConflictPolicy.Merge,
            merge = { l, r -> (l.decodeToString() + "+" + r.decodeToString()).toByteArray() },
        )
        drive.put("s.json", "remote")
        File(dir, "s.json").writeText("local")

        engine.sync(SyncIndex())

        assertEquals("local+remote", File(dir, "s.json").readText())
        assertEquals("local+remote", drive.content("s.json"))
    }

    @Test
    fun stateMissingHereIsFetchedAgainRatherThanRememberedAsDeleted() {
        val dir = tmp.newFolder("state")
        val engine = SyncEngine(
            DirectoryStore(dir, { true }),
            drive,
            ConflictPolicy.Merge,
            merge = { _, r -> r },
        )
        val index = SyncIndex()
        drive.put("s.properties", "state")
        engine.sync(index)
        File(dir, "s.properties").delete()

        engine.sync(index)

        assertEquals("state", File(dir, "s.properties").readText())
        assertTrue(index.deleted.isEmpty())
    }

    @Test
    fun theIndexSurvivesARoundTrip() {
        val index = SyncIndex()
        index.synced["a b.gpx"] = SyncIndex.Entry("id1", "md5")
        index.deleted += "gone.gpx"
        val file = File(tmp.root, "index.txt")

        index.write(file)
        val read = SyncIndex.read(file)

        assertEquals(index.synced, read.synced)
        assertEquals(index.deleted, read.deleted)
    }
}
