package org.tessellation.modules

import cats.effect.Async

import org.tessellation.config.types.AppConfig
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.domain.snapshot.SnapshotTrigger
import org.tessellation.infrastructure.snapshot.SnapshotTriggerPipeline
import org.tessellation.security.signature.Signed

import fs2._

object Streams {

  def make[F[_]: Async](
    storages: Storages[F],
    validators: Validators[F],
    queues: Queues[F],
    cfg: AppConfig
  ): Streams[F] = new Streams[F] {

    val snapshotTrigger = SnapshotTriggerPipeline.stream(
      storages.globalSnapshot,
      validators.snapshotPreconditions,
      queues.l1Output,
      cfg.snapshot
    )
  }

}

sealed abstract class Streams[F[_]] private {
  val snapshotTrigger: Stream[F, Either[Signed[DAGBlock], SnapshotTrigger]]
}
