package com.pbungert.geoimport.core.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class CameraSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val monday = Instant.parse("2026-09-28T08:00:00Z")
    private val tuesday = Instant.parse("2026-09-29T08:00:00Z")

    @Test
    fun survivesARoundTrip() {
        val settings = CameraSettings()
            .with(CameraSettings.PHOTO_TIME_ZONE, "Europe/Zurich", monday)
            .withExcludedTypes(setOf("MOV", "jpg"), tuesday)

        val read = CameraSettings.parse(settings.toBytes())

        assertEquals(settings, read)
        assertEquals(setOf("jpg", "mov"), read.excludedTypes)
    }

    @Test
    fun eachSettingKeepsItsOwnLaterChange() {
        // The tablet corrected the clock on Tuesday; the PC left out JPGs on
        // Monday and still has Monday's clock offset. Neither change may undo
        // the other.
        val tablet = CameraSettings()
            .with(CameraSettings.CLOCK_OFFSET_MINUTES, "2", monday)
            .with(CameraSettings.CLOCK_OFFSET_MINUTES, "-3", tuesday)
        val pc = CameraSettings()
            .with(CameraSettings.CLOCK_OFFSET_MINUTES, "2", monday)
            .withExcludedTypes(setOf("jpg"), monday)

        val merged = CameraSettings.merge(tablet, pc)

        assertEquals("-3", merged[CameraSettings.CLOCK_OFFSET_MINUTES])
        assertEquals(setOf("jpg"), merged.excludedTypes)
        assertEquals(merged, CameraSettings.merge(pc, tablet))
    }

    @Test
    fun settingTheSameValueAgainIsNoChange() {
        val settings = CameraSettings().with(CameraSettings.TOLERANCE_MINUTES, "30", monday)

        assertSame(settings, settings.with(CameraSettings.TOLERANCE_MINUTES, "30", tuesday))
    }

    @Test
    fun unreadableContentMergesAsEmpty() {
        val good = CameraSettings().with(CameraSettings.TOLERANCE_MINUTES, "45", monday)

        val merged = CameraSettings.parse(CameraSettings.mergeBytes("=\u0000garbage".toByteArray(), good.toBytes()))

        assertEquals("45", merged[CameraSettings.TOLERANCE_MINUTES])
    }

    @Test
    fun saveKeepsALaterChangeAlreadyOnDisk() {
        val file = CameraSettings.fileIn(tmp.root)
        CameraSettings.save(CameraSettings().with(CameraSettings.TOLERANCE_MINUTES, "45", tuesday), file)

        CameraSettings.save(CameraSettings().with(CameraSettings.TOLERANCE_MINUTES, "30", monday), file)

        assertEquals("45", CameraSettings.read(file)[CameraSettings.TOLERANCE_MINUTES])
    }

    @Test
    fun aMissingFileIsNoSettings() {
        assertNull(CameraSettings.read(CameraSettings.fileIn(tmp.root))[CameraSettings.TOLERANCE_MINUTES])
    }

    @Test
    fun typesAreReadLeniently() {
        assertEquals(setOf("jpg", "mov"), CameraSettings.parseTypes(" .JPG, mov,, "))
    }
}
