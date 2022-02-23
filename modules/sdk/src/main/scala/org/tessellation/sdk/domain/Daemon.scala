package org.tessellation.sdk.domain

import cats.effect.kernel.{Concurrent, Spawn}
import cats.syntax.functor._

import fs2._

trait Daemon[F[_]] {
  def start: F[Unit]
}

object Daemon {

  def spawn[F[_]: Spawn](thunk: F[Unit]): Daemon[F] = new Daemon[F] {
    def start: F[Unit] = Spawn[F].start(thunk).void
  }

  def drainStream[F[_]: Concurrent](stream: Stream[F, Unit]): Daemon[F] =
    spawn[F] { stream.compile.drain }
}
