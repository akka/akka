/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import scala.concurrent.duration._
import akka.util.Timeout
import akka.stream.{ MaterializerSettings, FlowMaterializer }
import akka.stream.scaladsl.Flow
import akka.io.IO
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.http.model._
import HttpMethods._

object TestClient extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher

  val materializer = FlowMaterializer(MaterializerSettings())
  implicit val askTimeout: Timeout = 500.millis
  val host = "spray.io"

  println(s"Fetching HTTP server version of host `$host` ...")

  val result = for {
    connection ← IO(Http).ask(Http.Connect(host)).mapTo[Http.OutgoingConnection]
    response ← sendRequest(HttpRequest(GET, uri = "/"), connection)
  } yield response.header[headers.Server]

  def sendRequest(request: HttpRequest, connection: Http.OutgoingConnection): Future[HttpResponse] = {
    Flow(List(HttpRequest() -> 'NoContext)).produceTo(materializer, connection.processor)
    Flow(connection.processor).map(_._1).toFuture(materializer)
  }

  result onComplete {
    case Success(res)   ⇒ println(s"$host is running ${res mkString ", "}")
    case Failure(error) ⇒ println(s"Error: $error")
  }
  result onComplete { _ ⇒ system.shutdown() }
}