// MELODY_BRIDGE_SPEC §8: the only control channel between the Melody panel and BtRemix.
//
// The service is exported but carries no android:permission (com.oplus.melody cannot request a
// BtRemix-declared permission). Every method re-checks Binder.getCallingUid() against the packages
// owned by that UID and rejects anything that is not com.oplus.melody (or BtRemix itself).
package com.Fusion.Btremix.melody.bridge;

import android.os.Bundle;
import com.Fusion.Btremix.melody.api.MelodySnapshot;
import com.Fusion.Btremix.melody.api.MelodySupportInfo;
import com.Fusion.Btremix.melody.bridge.IMelodyBridgeListener;

interface IMelodyBridge {
    /** Normalised MACs that currently have a live session in the process registry. */
    List<String> listManagedMacs();

    /** Synthesised whitelist identity for [mac]; null when the MAC is unknown or unmanaged. */
    MelodySupportInfo resolveSupport(in String mac);

    /** Current lifecycle + state for [mac]; a degraded snapshot when nothing is managed yet. */
    MelodySnapshot snapshot(in String mac);

    /** Executes one action through the shared session. Returns a MelodyBridgeResult code. */
    int execute(in String mac, in String actionId, in Bundle args);

    void register(in IMelodyBridgeListener listener);

    void unregister(in IMelodyBridgeListener listener);
}
