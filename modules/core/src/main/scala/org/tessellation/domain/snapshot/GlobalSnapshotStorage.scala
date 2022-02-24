package org.tessellation.domain.snapshot

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal, StateChannelSnapshotBinary}
import org.tessellation.schema.address.Address

trait GlobalSnapshotStorage[F[_]] {

  def save(globalSnapshot: GlobalSnapshot): F[Unit]

  def getLast: F[GlobalSnapshot]

  def get(ordinal: SnapshotOrdinal): F[Option[GlobalSnapshot]]

  def getStateChannelSnapshotUntilOrdinal(
    ordinal: SnapshotOrdinal
  )(address: Address): F[Option[StateChannelSnapshotBinary]]

}

trait GlobalSnapshotStorageV2[F[_]] {

  def prepend(snapshot: GlobalSnapshot): F[Boolean]

  def head: F[GlobalSnapshot]

  def get(ordinal: SnapshotOrdinal): F[Option[GlobalSnapshot]]

}
