/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import com.typesafe.config.{ Config, ConfigFactory }
import org.reactivestreams.Subscriber
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import akka.stream.scaladsl2.{ FlowMaterializer, Sink, Source }
import akka.io.IO
import akka.http.model.HttpMethods._
import akka.http.model._

object TestClient extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import akka.http.TestClient.system.dispatcher

  implicit val materializer = FlowMaterializer()
  implicit val askTimeout: Timeout = 500.millis
  val host = "spray.io"

  println(s"Fetching HTTP server version of host `$host` ...")

  val result = for {
    connection ← IO(Http).ask(Http.Connect(host)).mapTo[Http.OutgoingConnection]
    response ← sendRequest(HttpRequest(GET, uri = "/"), connection)
  } yield response.header[headers.Server]

  def sendRequest(request: HttpRequest, connection: Http.OutgoingConnection): Future[HttpResponse] = {
    Source(List(HttpRequest() -> 'NoContext))
      .connect(Sink(connection.requestSubscriber))
      .run()
    Source(connection.responsePublisher).map(_._1).runWith(Sink.future)
  }

  result onComplete {
    case Success(res)   ⇒ println(s"$host is running ${res mkString ", "}")
    case Failure(error) ⇒ println(s"Error: $error")
  }
  result onComplete { _ ⇒ system.shutdown() }
}