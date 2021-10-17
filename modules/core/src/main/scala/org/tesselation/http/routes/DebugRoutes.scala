package org.tesselation.http.routes

import cats.effect.kernel.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import org.tesselation.modules.{Services, Storages}
import org.tesselation.schema.cluster.SessionAlreadyExists
import org.tesselation.schema.node.InvalidNodeStateTransition
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class DebugRoutes[F[_]: Async](
  storages: Storages[F],
  services: Services[F]
) extends Http4sDsl[F] {

  private val logger = Slf4jLogger.getLogger[F]

  private[routes] val prefixPath = "/debug"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root                              => Ok()
    case GET -> Root / "registration" / "request" => Ok(services.cluster.getRegistrationRequest)
    case GET -> Root / "peers"                    => Ok(storages.cluster.getPeers)
    case POST -> Root / "create-session" =>
      services.session.createSession.flatMap(Ok(_)).recoverWith {
        case e: InvalidNodeStateTransition => Conflict(e.getMessage)
        case SessionAlreadyExists          => Conflict(s"Session already exists.")
      }
    case POST -> Root / "logger" / "info" / text  => logger.info(text) >> Ok()
    case POST -> Root / "logger" / "debug" / text => logger.debug(text) >> Ok()
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
