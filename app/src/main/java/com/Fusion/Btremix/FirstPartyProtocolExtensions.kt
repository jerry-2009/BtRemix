package com.Fusion.Btremix

import com.Fusion.Btremix.protocol.extension.ProtocolExtensionRegistry
import com.Fusion.Btremix.vendor.testbed.ReferenceChallengeExtension

/**
 * Application composition root for compiled first-party protocol extensions.
 *
 * Keep this list explicit. Package manifests resolve only these registrations and never inspect
 * the classpath or load implementation artifacts from a `.dcpkg`.
 */
internal fun firstPartyProtocolExtensions(): ProtocolExtensionRegistry =
    ProtocolExtensionRegistry(listOf(ReferenceChallengeExtension()))
