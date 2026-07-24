package com.heirloom.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RestorationSessionTest {
    @After
    fun resetSession() {
        RestorationSession.update(RestoreState.Idle)
    }

    @Test
    fun sessionStateSurvivesUiSubscriberReplacement() {
        val state = RestoreState.Failed(
            source = null,
            message = "Connection interrupted",
        )

        RestorationSession.update(state)

        assertEquals(state, RestorationSession.state.value)
    }
}
