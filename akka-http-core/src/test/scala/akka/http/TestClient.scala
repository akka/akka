/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import com.typesafe.config.{ Config, ConfigFactory }
import scala.util.{ Failure, Success }
import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.http.model._

object TestClient extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val fm = ActorFlowMaterializer()
  import system.dispatcher

  val host = "spray.io"

  println(s"Fetching HTTP server version of host `$host` ...")

  val connection = Http().outgoingConnection(host)
  val result = Source.single(HttpRequest()).via(connection).runWith(Sink.head())

  result.map(_.header[headers.Server]) onComplete {
    case Success(res)   ⇒ println(s"$host is running ${res mkString ", "}")
    case Failure(error) ⇒ println(s"Error: $error")
  }
  result onComplete { _ ⇒ system.shutdown() }
}