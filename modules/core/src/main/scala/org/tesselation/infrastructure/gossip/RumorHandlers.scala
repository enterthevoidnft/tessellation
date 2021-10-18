package org.tesselation.infrastructure.gossip

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.semigroupk._

import org.tesselation.kryo.KryoSerializer

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RumorHandlers {

  def debugHandlers[F[_]: Async: KryoSerializer]: RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    val strHandler = RumorHandler.fromFn[F, String] { s =>
      logger.info(s"String rumor received $s")
    }

    val optIntHandler = RumorHandler.fromFn[F, Option[Int]] {
      case Some(i) if i > 0 => logger.info(s"Int rumor received $i")
      case o =>
        MonadThrow[F].raiseError(new RuntimeException(s"Int rumor error ${o.map(_.toString).getOrElse("none")}"))
    }

    strHandler <+> optIntHandler
  }
}
