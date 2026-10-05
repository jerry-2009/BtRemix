package com.fusion.melodyLinkNeo.melody.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fusion.melodyLinkNeo.melody.api.MelodyDoorbellProtocol

/**
 * M5.4b (2026-10-05 review follow-up): the injected `com.oplus.melody` process tells us it just came
 * up, so we can answer with one immediate doorbell instead of letting it wait for the next keepalive.
 *
 * The component is declared `enabled="false"` and is only switched on while [MelodySessionService] is
 * alive ([MelodySessionService.setHelloReceiverEnabled]). Two consequences, both wanted:
 *
 * - a hello can never *start* the BtRemix process when the bridge is not running (there is nothing to
 *   hand out then), and the spoofing surface only exists while the service runs;
 * - the receiver always runs in the same process as the service, so [MelodySessionService.requestDoorbellFromHost]
 *   can hand the request straight to the live doorbell sender.
 *
 * A spoofed hello is harmless by construction: it can only cause one extra doorbell broadcast, and
 * every binder call the host then makes is still `Binder.getCallingUid()`-checked on our side.
 */
class MelodyHostHelloReceiver : BroadcastReceiver() {

    private val log = MelodyBridgeLog()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != MelodyDoorbellProtocol.HELLO_ACTION) return
        val protocol = intent.getIntExtra(MelodyDoorbellProtocol.EXTRA_PROTOCOL, 0)
        if (!MelodyDoorbellProtocol.isCompatible(protocol)) return
        val rang = MelodySessionService.requestDoorbellFromHost()
        log.event(
            "melody.bridge.hello",
            "protocol" to protocol,
            "ring" to rang,
            "caller_uid" to runCatching { android.os.Binder.getCallingUid() }.getOrNull(),
        )
    }
}
