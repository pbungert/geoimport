package com.pbungert.geoimport.core.imports

import com.pbungert.geoimport.core.spi.ExifDateReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

class CachingExifDateReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val noon = LocalDateTime.parse("2026-07-17T12:00:00")

    @Test
    fun readsEachUnchangedFileOnce() {
        val reads = AtomicInteger()
        val reader = CachingExifDateReader(ExifDateReader { reads.incrementAndGet(); noon to null })
        val file = tmp.newFile("DSCF0001.RAF").also { it.writeText("x") }

        repeat(3) { assertEquals(noon to null, reader.readDateTaken(file)) }
        assertEquals(1, reads.get())
    }

    @Test
    fun remembersThatAFileHasNoDate() {
        val reads = AtomicInteger()
        val reader = CachingExifDateReader(ExifDateReader { reads.incrementAndGet(); null })
        val file = tmp.newFile("DSCF0001.MOV").also { it.writeText("x") }

        repeat(2) { assertNull(reader.readDateTaken(file)) }
        assertEquals(1, reads.get())
    }

    @Test
    fun readsAgainWhenTheFileChanged() {
        val reads = AtomicInteger()
        val reader = CachingExifDateReader(ExifDateReader { reads.incrementAndGet(); noon to null })
        val file = tmp.newFile("DSCF0001.RAF").also { it.writeText("x") }

        reader.readDateTaken(file)
        // Another card with the same name on it: different size, different time.
        file.writeText("another photo")
        file.setLastModified(file.lastModified() + 60_000)
        reader.readDateTaken(file)
        assertEquals(2, reads.get())
    }

    @Test
    fun parallelMapKeepsTheOrder() {
        val items = (1..200).toList()
        assertEquals(items.map { it * 2 }, items.mapInParallel(4) { Thread.sleep(1); it * 2 })
    }

    @Test(expected = IOException::class)
    fun parallelMapRethrowsTheOriginalException() {
        (1..10).toList().mapInParallel(4) { if (it == 7) throw IOException("card removed") else it }
    }
}
