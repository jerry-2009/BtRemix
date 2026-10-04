// MELODY_BRIDGE_SPEC §8: push channel from BtRemix back into the Melody panel.
package com.Fusion.Btremix.melody.bridge;

import com.Fusion.Btremix.melody.api.MelodySnapshot;

interface IMelodyBridgeListener {
    void onSnapshot(in String mac, in MelodySnapshot snapshot);
    void onSupportChanged();
}
