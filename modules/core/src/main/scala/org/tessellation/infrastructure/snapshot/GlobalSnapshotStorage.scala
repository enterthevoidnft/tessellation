package org.tessellation.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{Async, Ref}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._

import org.tessellation.dag.snapshot._
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

object GlobalSnapshotStorage {

  def make[F[_]: Async: KryoSerializer](genesis: Signed[GlobalSnapshot]): F[GlobalSnapshotStorage[F]] =
    Ref[F].of[NonEmptyList[Signed[GlobalSnapshot]]](NonEmptyList.of(genesis)).map(make(_))

  private def make[F[_]: Async: KryoSerializer](
    snapshotsRef: Ref[F, NonEmptyList[Signed[GlobalSnapshot]]]
  ): GlobalSnapshotStorage[F] = new GlobalSnapshotStorage[F] {

    def save(snapshot: Signed[GlobalSnapshot]): F[Unit] =
      snapshotsRef.modify { snapshots =>
        val lastSnapshot = snapshots.head
        lastSnapshot.value.hash match {
          case Left(error) => (snapshots, error.raiseError[F, Unit])
          case Right(lastSnapshotHash) =>
            val expectedLink = (lastSnapshotHash, lastSnapshot.ordinal.next)
            val actualLink = (snapshot.lastSnapshotHash, snapshot.ordinal)
            if (expectedLink === actualLink) {
              (snapshot :: snapshots, Applicative[F].unit)
            } else {
              (snapshots, InvalidGlobalSnapshotChain(expectedLink, actualLink).raiseError[F, Unit])
            }
        }
      }.flatten

    def get(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] =
      snapshotsRef.get.map(_.find(_.ordinal === ordinal))

    def getLast: F[Signed[GlobalSnapshot]] = snapshotsRef.get.map(_.head)

    def getGlobalSnapshotInfo(ordinal: SnapshotOrdinal): F[GlobalSnapshotInfo] =
      snapshotsRef.get.map { snapshots =>
        val lastStateChannelSnapshotHashes = snapshots
          .filter(_.ordinal <= ordinal)
          .foldLeft(Map.empty[Address, Hash]) { (acc, globalSnapshot) =>
            acc ++
              globalSnapshot.stateChannelSnapshots.view
                .filterKeys(!acc.contains(_))
                .mapValues(nel => Hash.fromBytes(nel.head.content))
                .toMap
          }
        GlobalSnapshotInfo(ordinal, lastStateChannelSnapshotHashes)
      }
  }

  type GlobalSnapshotLink = (Hash, SnapshotOrdinal)

  case class InvalidGlobalSnapshotChain(expected: GlobalSnapshotLink, actual: GlobalSnapshotLink)
      extends Throwable(s"Expected link $expected, actual link $actual")

}
