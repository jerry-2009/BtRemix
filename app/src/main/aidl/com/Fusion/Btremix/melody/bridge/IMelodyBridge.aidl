// MELODY_BRIDGE_SPEC §8: the only control channel between the Melody panel and BtRemix.
//
// The service is exported but carries no android:permission (com.oplus.melody cannot request a
// BtRemix-declared permission). Every method re-checks Binder.getCallingUid() against the packages
// owned by that UID and rejects anything that is not com.oplus.melody (or BtRemix itself).
//
// !! NEVER INSERT OR REORDER METHODS - ALWAYS APPEND. !!
//
// AIDL numbers transactions by *declaration order* (`TRANSACTION_x = FIRST_CALL_TRANSACTION + n`), and
// this interface is spread over two processes that can run two different module dex builds: LSPosed
// reads the module APK when a process starts, so after installing a new module the *host* process
// (com.oplus.melody) keeps the previous build's proxy until it is restarted, while the BtRemix process
// (which owns the stub) is killed and restarted by the install itself. Inserting a method in the middle
// therefore makes the stale side's `execute` land on the new method - and the visible symptom is not an
// exception but "the device card's mode switch silently does nothing" (the first four calls still line
// up, so the panel and the header keep looking healthy). Appending keeps every existing number, so a
// skew only costs the new feature until the host is restarted.
package com.Fusion.Btremix.melody.bridge;

import android.os.Bundle;
import com.Fusion.Btremix.melody.api.MelodySnapshot;
import com.Fusion.Btremix.melody.api.MelodySupportInfo;
import com.Fusion.Btremix.melody.bridge.IMelodyBridgeListener;

interface IMelodyBridge {
    /**
     * Normalised MACs the host should treat as supported: live sessions plus paired devices claimed
     * by a Definition with a `melody` section (M3-D6).
     */
    List<String> listManagedMacs();

    /** Synthesised whitelist identity for [mac]; identity fields are empty when only a session exists. */
    MelodySupportInfo resolveSupport(in String mac);

    /**
     * M3: projection envelope (UTF-8 JSON) for one managed MAC, or null when the MAC has no
     * Definition with a `melody` section. The envelope carries the synthesised WhitelistConfigDTO
     * plus the M4 panel policy; it is built inside BtRemix so the host only has to cache and apply it.
     */
    String resolveProjection(in String mac);

    /** Current lifecycle + state for [mac]; a degraded snapshot when nothing is managed yet. */
    MelodySnapshot snapshot(in String mac);

    /** Executes one action through the shared session. Returns a MelodyBridgeResult code. */
    int execute(in String mac, in String actionId, in Bundle args);

    void register(in IMelodyBridgeListener listener);

    void unregister(in IMelodyBridgeListener listener);

    /**
     * M5.4 D-28: one structured diagnostic event from the host process (whitelist in
     * MelodyDiagnosticPolicy). `oneway`: the host must never wait on our ring buffer, and the extra
     * data is not part of the control contract.
     */
    oneway void reportDiagnostics(in String name, in Bundle fields);

    /**
     * M5.4 D-31: a host process that just attached asks for one extra doorbell broadcast, so the
     * other host process (`:fg`) does not have to wait for the 30 s keepalive.
     */
    oneway void requestDoorbell();

    /**
     * The managed Definition's own `assets/icon.png` for [mac], or null when the MAC is unmanaged or
     * its package ships no icon. Used by the host-side detail / OneSpace artwork injection so Melody's
     * generic placeholder picture can be replaced by the device package's own picture.
     *
     * Appended on purpose - see the ordering warning at the top of this file.
     */
    byte[] resolveIcon(in String mac);
}
