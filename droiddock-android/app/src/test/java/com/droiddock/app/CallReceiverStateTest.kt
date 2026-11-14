package com.droiddock.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Literal values of TelephonyManager.EXTRA_STATE_* and CALL_STATE_*: the class itself cannot load on the JVM.
private const val IDLE = 0
private const val RINGING = 1
private const val OFFHOOK = 2

class CallReceiverStateTest {
    @Test fun `a call claimed while a call is really in progress is accepted`() {
        assertTrue(CallReceiver.stateMatches("RINGING", RINGING))
        assertTrue(CallReceiver.stateMatches("OFFHOOK", OFFHOOK))
        // The broadcast can lag: answered at once, the phone is already off-hook when RINGING arrives.
        assertTrue(CallReceiver.stateMatches("RINGING", OFFHOOK))
    }

    @Test fun `a forged call on an idle phone is rejected`() {
        assertFalse(CallReceiver.stateMatches("RINGING", IDLE))
        assertFalse(CallReceiver.stateMatches("OFFHOOK", IDLE))
    }

    @Test fun `idle always clears, even if the next call is already ringing`() {
        assertTrue(CallReceiver.stateMatches("IDLE", IDLE))
        assertTrue(CallReceiver.stateMatches("IDLE", RINGING))
    }

    @Test fun `unreadable real state keeps current behaviour`() {
        assertTrue(CallReceiver.stateMatches("RINGING", null))
    }
}
