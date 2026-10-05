// MELODY_BRIDGE_SPEC §8: push channel from BtRemix back into the Melody panel.
package com.fusion.melodyLinkNeo.melody.bridge;

import com.fusion.melodyLinkNeo.melody.api.MelodySnapshot;

interface IMelodyBridgeListener {
    void onSnapshot(in String mac, in MelodySnapshot snapshot);
    void onSupportChanged();
}
