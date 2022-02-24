package org.tessellation.infrastructure.snapshot

import cats.Applicative
import cats.data.EitherT
import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.storage.LocalFileStorage

final class GlobalSnapshotLocalFileStorage[F[_]: Async: KryoSerializer] private (path: String)
    extends LocalFileStorage[F, GlobalSnapshot](path) {

  def write(snapshot: GlobalSnapshot): EitherT[F, Throwable, Unit] =
    write(snapshot.ordinal.value.value.toString(), snapshot)

  def read(ordinal: SnapshotOrdinal): EitherT[F, Throwable, GlobalSnapshot] =
    read(ordinal.value.value.toString())

}

object GlobalSnapshotLocalFileStorage {

  def make[F[_]: Async: KryoSerializer](path: String): F[GlobalSnapshotLocalFileStorage[F]] =
    Applicative[F].pure { new GlobalSnapshotLocalFileStorage[F](path) }.flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
