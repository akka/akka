/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.scaladsl

//#bindAndHandleAsync
import akka.http.scaladsl.Http

//#bindAndHandleAsync
import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }

object Http2Spec {
  val asyncHandler = ???
  val httpsServerContext = ???
  implicit val system: ActorSystem = ???
  implicit val materializer: Materializer = ???

  //#bindAndHandleAsync
  Http().bindAndHandleAsync(asyncHandler, interface = "localhost", port = 8443, httpsServerContext)
  //#bindAndHandleAsync
}
