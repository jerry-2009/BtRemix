package c7

import java.util.UUID

/**
 * Stand-in for an R8 short-package class (`c7.b` in the 17.6.3 host).
 *
 * It exists to pin the M6 regression: the first cut's `com.oplus./com.coui.` allow list rejected exactly
 * this kind of baseline, which is why `transport.client`, `transport.write`, `whitelist.repo_mapper`,
 * `panel.group_observer`, `card.menu_builder`, `anc.refresh_vo` and `card.data_sender` all reported
 * "missing" after the first real deployment.
 */
open class b {
    @Suppress("unused")
    fun o(id: UUID) {
        id.hashCode()
    }
}
