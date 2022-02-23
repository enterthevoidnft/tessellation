package org.tessellation.dag.snapshot

import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash

import derevo.cats.show
import derevo.derive

@derive(show)
case class GlobalSnapshotInfo(
  ordinal: SnapshotOrdinal,
  lastStateChannelSnapshotHashes: Map[Address, Hash]
)
