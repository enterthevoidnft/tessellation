package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.std.Queue
import cats.effect.{Async, Ref, Spawn}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq.catsSyntaxEq
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal, StateChannelSnapshotBinary}
import org.tessellation.domain.snapshot.{GlobalSnapshotStorage, GlobalSnapshotStorageV2}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.cats.syntax.partialPrevious._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address._
import org.tessellation.security.hash.Hash

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GlobalSnapshotStorage {

  def make[F[_]: Async: KryoSerializer](genesis: GlobalSnapshot): F[GlobalSnapshotStorage[F]] =
    Ref[F].of[NonEmptyList[GlobalSnapshot]](NonEmptyList.of(genesis)).map(make(_))

  def make[F[_]: Async: KryoSerializer](
    snapshotsRef: Ref[F, NonEmptyList[GlobalSnapshot]]
  ): GlobalSnapshotStorage[F] = new GlobalSnapshotStorage[F] {

    def save(snapshot: GlobalSnapshot): F[Unit] =
      snapshotsRef.modify { snapshots =>
        val lastSnapshot = snapshots.head
        lastSnapshot.hash match {
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

    def get(ordinal: SnapshotOrdinal): F[Option[GlobalSnapshot]] =
      snapshotsRef.get.map(_.find(_.ordinal === ordinal))

    def getLast: F[GlobalSnapshot] = snapshotsRef.get.map(_.head)

    def getStateChannelSnapshotUntilOrdinal(
      ordinal: SnapshotOrdinal
    )(address: Address): F[Option[StateChannelSnapshotBinary]] =
      snapshotsRef.get.map { snapshots =>
        snapshots.find { snapshot =>
          snapshot.ordinal <= ordinal && snapshot.stateChannelSnapshots.contains(address)
        }.flatMap { snapshot =>
          snapshot.stateChannelSnapshots.get(address).map(_.head)
        }
      }
  }

  type GlobalSnapshotLink = (Hash, SnapshotOrdinal)

  case class InvalidGlobalSnapshotChain(expected: GlobalSnapshotLink, actual: GlobalSnapshotLink) extends NoStackTrace

}

object GlobalSnapshotStorageV2 {

  def make[F[_]: Async: KryoSerializer](
    globalSnapshotLocalFileStorage: GlobalSnapshotLocalFileStorage[F],
    genesis: GlobalSnapshot,
    inMemoryCapacity: PosInt
  ): F[GlobalSnapshotStorageV2[F]] = {
    def mkCacheQueue = Queue.unbounded[F, GlobalSnapshot]
    def mkHeadRef = Ref.of[F, GlobalSnapshot](genesis)
    def mkCache = MapRef.ofSingleImmutableMap[F, SnapshotOrdinal, GlobalSnapshot](Map.empty)
    def mkOffloadQueue = Queue.unbounded[F, SnapshotOrdinal]

    def mkLogger = Slf4jLogger.create[F]

    mkLogger.flatMap { implicit logger =>
      (mkCacheQueue, mkHeadRef, mkCache, mkOffloadQueue).mapN {
        make(_, _, _, _, globalSnapshotLocalFileStorage, inMemoryCapacity)
      }.flatten
    }
  }

  def make[F[_]: Async: Logger: KryoSerializer](
    cacheQueue: Queue[F, GlobalSnapshot],
    headRef: Ref[F, GlobalSnapshot],
    cache: MapRef[F, SnapshotOrdinal, Option[GlobalSnapshot]],
    offloadQueue: Queue[F, SnapshotOrdinal],
    globalSnapshotLocalFileStorage: GlobalSnapshotLocalFileStorage[F],
    inMemoryCapacity: PosInt
  ): F[GlobalSnapshotStorageV2[F]] = {

    def cacheProcess =
      Stream
        .fromQueueUnterminated(cacheQueue)
        .evalTap { snapshot =>
          headRef.set(snapshot) >>
            cache(snapshot.ordinal).set(snapshot.some) >>
            snapshot.ordinal
              .partialPreviousN(NonNegLong.unsafeFrom(inMemoryCapacity.toLong))
              .fold(Applicative[F].unit)(offloadQueue.offer(_))
        }
        .compile
        .drain

    def offloadProcess =
      Stream
        .fromQueueUnterminated(offloadQueue)
        .evalTap { ordinal =>
          cache(ordinal).get.flatMap {
            case Some(snapshot) =>
              globalSnapshotLocalFileStorage.write(snapshot).rethrowT.handleErrorWith { e =>
                Logger[F].error(e)(s"Failed writing global snapshot to disk! Snapshot ordinal=${snapshot.ordinal.show}")
              } >> cache(ordinal).set(none)
            case None => MonadThrow[F].raiseError[Unit](new Throwable("Unexpected state!"))
          }
        }
        .compile
        .drain

    Spawn[F].start { offloadProcess } >> Spawn[F].start { cacheProcess }.map { _ =>
      new GlobalSnapshotStorageV2[F] {
        def prepend(snapshot: GlobalSnapshot) =
          isNextSnapshot(snapshot).flatTap {
            if (_) {
              cacheQueue.offer(snapshot)
            } else {
              Applicative[F].unit
            }
          }

        def head = headRef.get

        def get(ordinal: SnapshotOrdinal) = cache(ordinal).get.flatMap {
          case Some(s) => s.some.pure[F]
          case None    => globalSnapshotLocalFileStorage.read(ordinal).value.map(_.toOption)
        }

        private def isNextSnapshot(snapshot: GlobalSnapshot): F[Boolean] =
          head.flatMap(s => s.hashF.map((_, s))).map {
            case (hash, s) => hash === snapshot.lastSnapshotHash && s.ordinal.next === snapshot.ordinal
          }

      }
    }
  }

}
