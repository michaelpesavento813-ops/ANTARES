package com.antares.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExportRulesTest {
    @Test fun csvQuotesAndSeparatesDataAndNumericValues() {
        assertEquals("\"image \"\"1\"\", side.png\"", csvCell("image \"1\", side.png"))
        assertEquals("\"\"", csvCell(null))
        assertEquals("\"-0.25\"", csvCell(-0.25))
        assertEquals("\"'=1+1\"", csvCell("=1+1"))
        assertEquals("\"'  @A1\"", csvCell("  @A1"))
        assertEquals("\"'\tfile\"", csvCell("\tfile"))
    }

    @Test fun aliasesAreStableWithoutCollisionsAcrossThousandsOfFiles() {
        val ids = (1L..5000).shuffled(Random(194351))
        val names = ids.map { exportName("ANT", it, "CSV") }
        assertEquals(ids.size, names.toSet().size)
        assertTrue(names.all { it.matches(Regex("[A-Z0-9]{8}\\.CSV")) })
        assertNotEquals(exportName("IMG", 70, "PNG"), exportName("IMG", 71, "PNG"))
        assertEquals("ANT00001.CSV", exportName("ANT", 1, "CSV"))
    }
}
