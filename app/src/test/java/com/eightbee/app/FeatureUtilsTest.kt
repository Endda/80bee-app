package com.eightbee.app

import com.eightbee.app.features.parseKeyValueOutput
import com.eightbee.app.features.parsePmListPackages
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureUtilsTest {

    @Test
    fun parsePmListPackages_parsesTrimmedPackageIds() {
        val raw = """
            package:com.example.one
              package:com.example.two
            package:
            unrelated line
        """.trimIndent()
        assertEquals(listOf("com.example.one", "com.example.two"), parsePmListPackages(raw))
    }

    @Test
    fun parseKeyValueOutput_extractsMarkerPairsAndIgnoresNoise() {
        val raw = """
            WARNING: linker warning
            __K_dns_mode__=hostname
            __K_matrix__=0.5,0,0,0
            __K_empty__=
        """.trimIndent()

        val parsed = parseKeyValueOutput(raw)
        assertEquals("hostname", parsed["dns_mode"])
        assertEquals("0.5,0,0,0", parsed["matrix"])
        assertEquals("", parsed["empty"])
        assertEquals(3, parsed.size)
    }
}
