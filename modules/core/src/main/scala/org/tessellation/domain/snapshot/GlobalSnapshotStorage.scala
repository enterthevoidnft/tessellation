package org.tessellation.domain.snapshot

import org.tessellation.dag.snapshot._
import org.tessellation.security.signature.Signed

trait GlobalSnapshotStorage[F[_]] {

  def save(globalSnapshot: Signed[GlobalSnapshot]): F[Unit]

  def getLast: F[Signed[GlobalSnapshot]]

  def get(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]]

  def getGlobalSnapshotInfo(ordinal: SnapshotOrdinal): F[GlobalSnapshotInfo]

}
