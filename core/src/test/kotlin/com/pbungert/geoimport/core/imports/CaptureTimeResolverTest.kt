package com.pbungert.geoimport.core.imports

import com.pbungert.geoimport.core.spi.ExifDateReader
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CaptureTimeResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val noon = LocalDateTime.parse("2026-07-17T12:00:00")
    private val berlin = ZoneId.of("Europe/Berlin") // UTC+2 in July
    private val tokyo = ZoneId.of("Asia/Tokyo") // UTC+9 year-round

    private fun reader(local: LocalDateTime?, offset: ZoneOffset? = null) =
        ExifDateReader { local?.let { it to offset } }

    private fun file(name: String = "DSCF0001.RAF") =
        tmp.newFile(name).also { it.writeText("x") }

    @Test
    fun usesTheCameraRecordedOffsetWhenThereIsOne() {
        val resolver = CaptureTimeResolver(reader(noon, ZoneOffset.ofHours(9)), assumedZone = berlin)
        // The offset wins; the assumed zone must not be consulted.
        assertEquals(Instant.parse("2026-07-17T03:00:00Z"), resolver.instantOf(file()))
    }

    @Test
    fun fallsBackToTheAssumedZoneWhenTheCameraRecordedNoOffset() {
        val resolver = CaptureTimeResolver(reader(noon), assumedZone = tokyo)
        assertEquals(Instant.parse("2026-07-17T03:00:00Z"), resolver.instantOf(file()))
    }

    @Test
    fun theAssumedZoneIsWhatSeparatesAHomeImportFromATripImport() {
        val shotInTokyo = CaptureTimeResolver(reader(noon), assumedZone = tokyo).instantOf(file("a.RAF"))
        val assumedHome = CaptureTimeResolver(reader(noon), assumedZone = berlin).instantOf(file("b.RAF"))
        assertEquals(Duration.ofHours(7), Duration.between(shotInTokyo, assumedHome))
    }

    @Test
    fun addsTheCameraClockOffsetToTheInstant() {
        val resolver = CaptureTimeResolver(
            reader(noon, ZoneOffset.UTC),
            cameraClockOffset = Duration.ofMinutes(-2),
        )
        assertEquals(Instant.parse("2026-07-17T11:58:00Z"), resolver.instantOf(file()))
    }

    @Test
    fun acceptsAFractionalClockOffset() {
        val resolver = CaptureTimeResolver(
            reader(noon, ZoneOffset.UTC),
            cameraClockOffset = Duration.ofSeconds(150),
        )
        assertEquals(Instant.parse("2026-07-17T12:02:30Z"), resolver.instantOf(file()))
    }

    @Test
    fun localTimeIgnoresBothZoneAndClockOffset() {
        // The resume timestamp a user types is read off the camera, so it must
        // compare against the uncorrected reading.
        val resolver = CaptureTimeResolver(
            reader(noon),
            assumedZone = tokyo,
            cameraClockOffset = Duration.ofHours(-5),
        )
        assertEquals(noon, resolver.localOf(file()))
    }

    @Test
    fun fallsBackToTheEarlierFileTimestampWhenThereIsNoExif() {
        val f = file()
        val stamp = 1_600_000_000_000L
        f.setLastModified(stamp)
        val resolver = CaptureTimeResolver(reader(null), assumedZone = ZoneOffset.UTC)
        // Creation time may be "now" for a freshly made file, so the minimum
        // must be the older last-modified value.
        assertEquals(Instant.ofEpochMilli(stamp), resolver.instantOf(f))
    }

    @Test
    fun aThrowingReaderDoesNotBreakTheImport() {
        val boom = ExifDateReader { error("corrupt header") }
        val f = file()
        f.setLastModified(1_600_000_000_000L)
        val resolver = CaptureTimeResolver(boom, assumedZone = ZoneOffset.UTC)
        assertEquals(Instant.ofEpochMilli(1_600_000_000_000L), resolver.instantOf(f))
    }
}
