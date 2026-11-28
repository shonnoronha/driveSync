package io.github.drivesync.feature.settings.settings

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FullDriveSwitchTest {
    /** A scripted Google: what [request] returns, whether [finish] grants, and what was saved. */
    private class Script(
        var screen: String? = null,
        var granted: Boolean = true,
        var requestFails: Boolean = false,
    ) : FullDriveGoogle<String> {
        val events = mutableListOf<String>()

        override suspend fun request(): String? {
            events += "request"
            if (requestFails) error("scope not on the consent screen")
            return screen
        }

        override suspend fun finish(result: Any?): Boolean {
            events += "finish($result)"
            return granted
        }
    }

    private fun switch(
        google: Script,
        available: Boolean = true,
    ) = FullDriveSwitch(available, google, { google.events += "enable" }, { google.events += "disable" })

    @Test
    fun `already granted turns on at once`() =
        runTest {
            val google = Script()
            switch(google).turnOn()
            assertEquals(listOf("request", "enable"), google.events)
        }

    @Test
    fun `consent screen then grant turns on`() =
        runTest {
            val google = Script(screen = "consent")
            val s = switch(google)

            s.turnOn()
            assertEquals("consent", s.consent.value)
            assertEquals(listOf("request"), google.events)

            s.consentResult("ok")
            assertNull(s.consent.value)
            assertEquals(listOf("request", "finish(ok)", "enable"), google.events)
            assertNull(s.message.value)
        }

    @Test
    fun `declined consent stays off`() =
        runTest {
            val google = Script(screen = "consent", granted = false)
            val s = switch(google)

            s.turnOn()
            s.consentResult(null)

            assertEquals(listOf("request", "finish(null)"), google.events)
            assertEquals(FullDriveMessage.NOT_GRANTED, s.message.value)
        }

    @Test
    fun `a refused request stays off`() =
        runTest {
            val google = Script(requestFails = true)
            val s = switch(google)

            s.turnOn()

            assertEquals(listOf("request"), google.events)
            assertEquals(FullDriveMessage.FAILED, s.message.value)
        }

    @Test
    fun `unavailable build never asks Google`() =
        runTest {
            val google = Script()
            switch(google, available = false).turnOn()
            assertEquals(emptyList<String>(), google.events)
        }

    @Test
    fun `turning off needs no consent`() =
        runTest {
            val google = Script()
            switch(google).turnOff()
            assertEquals(listOf("disable"), google.events)
        }
}
