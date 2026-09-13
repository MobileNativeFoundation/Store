package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Identity associating one acknowledged optimistic prefix with its committed source observation.
 *
 * Create a distinct identity for each acknowledgement generation and associate it with that
 * prefix before calling [StoreWriteHandle.applyAcknowledgement]. [Overlay] receives the identity
 * only for a source observation proven to include that commit. Metadata-only successors retain
 * it; unrelated writes and unproven raw observations do not. Identity uses reference equality.
 *
 * This evidence is transient. A reopened journal must reconcile ambiguous acknowledgement/source
 * crash states before projecting them; this identity provides no durable adoption proof.
 */
@ExperimentalStoreApi
public class SourceAdoption public constructor()
