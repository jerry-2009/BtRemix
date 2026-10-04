package com.Fusion.Btremix.melody.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Fusion.Btremix.melody.api.MelodyDoorbellProtocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the one assumption the doorbell transport rests on (MELODY_BRIDGE_TRANSPORT_PLAN.md §11.1):
 * an `IBinder` carried in a package-restricted broadcast's extras survives delivery, together with the
 * generation/protocol extras the receiver validates.
 *
 * The instrumentation process shares BtRemix's UID, so this is the same-process leg; the cross-UID hop
 * (`com.oplus.melody` receiving from BtRemix) still needs the manual on-device run described in the plan's
 * §11.3, because no test can fake that UID.
 */
@RunWith(AndroidJUnit4::class)
class MelodyDoorbellBroadcastTest {

    @Test
    fun doorbellBroadcast_carriesBinderAndProtocolExtras() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val received = LinkedBlockingQueue<Bundle>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(source: Context?, intent: Intent?) {
                received.offer(intent?.extras ?: Bundle())
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(MelodyDoorbellProtocol.ACTION),
            Context.RECEIVER_EXPORTED,
        )
        try {
            val extras = Bundle().apply {
                putBinder(MelodyDoorbellProtocol.EXTRA_BRIDGE, Binder())
                putInt(MelodyDoorbellProtocol.EXTRA_GENERATION, GENERATION)
                putInt(MelodyDoorbellProtocol.EXTRA_PROTOCOL, MelodyDoorbellProtocol.VERSION)
                putString(MelodyDoorbellProtocol.EXTRA_SENDER, SENDER)
            }
            context.sendBroadcast(
                Intent(MelodyDoorbellProtocol.ACTION)
                    .setPackage(context.packageName)
                    .putExtras(extras),
            )

            val delivered = received.poll(5, TimeUnit.SECONDS)
            assertNotNull("doorbell broadcast was not delivered", delivered)
            assertNotNull(
                "the binder did not survive the broadcast extras",
                delivered!!.getBinder(MelodyDoorbellProtocol.EXTRA_BRIDGE),
            )
            assertEquals(GENERATION, delivered.getInt(MelodyDoorbellProtocol.EXTRA_GENERATION, -1))
            assertEquals(
                MelodyDoorbellProtocol.VERSION,
                delivered.getInt(MelodyDoorbellProtocol.EXTRA_PROTOCOL, -1),
            )
            assertEquals(SENDER, delivered.getString(MelodyDoorbellProtocol.EXTRA_SENDER))
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val GENERATION = 7
        const val SENDER = "instrumentation"
    }
}
