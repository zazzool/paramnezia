package net.paramnezia.app.ota

import kotlinx.coroutines.runBlocking
import net.paramnezia.app.data.Repo
import net.paramnezia.app.platform.StateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckScheduleTest {
    @Test
    fun retriesFailureWithoutWaitingForTheRegularInterval() = runBlocking {
        val repo = Repo(object : StateStore {
            override val lastProblem: String? = null
            override fun read(): String? = null
            override fun write(text: String) = Unit
            override fun quarantine() = Unit
        })
        var now = 1_000_000L
        val schedule = UpdateCheckSchedule(repo) { now }
        var calls = 0
        assertFalse(schedule.check { calls++; false })
        assertEquals(0L, repo.current.lastUpdateCheck)
        now += 4 * 60_000L
        assertFalse(schedule.check { calls++; true })
        assertEquals(1, calls)
        now += 60_000L
        assertTrue(schedule.check { calls++; true })
        assertEquals(now, repo.current.lastUpdateCheck)
        now += 60_000L
        assertFalse(schedule.check { calls++; true })
        assertFalse(schedule.check(force = true) { calls++; false })
        assertEquals(3, calls)
        now += 5 * 60_000L
        assertTrue(schedule.check { calls++; true })
        assertEquals(4, calls)
    }
}
