/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import com.typesafe.config.{ Config, ConfigFactory }
import scala.util.{ Failure, Success }
import akka.actor.{ UnhandledMessage, ActorSystem }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.http.scaladsl.model._
import akka.http.impl.util._

object TestClient extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.log-dead-letters = off
    akka.io.tcp.trace-logging = off""")
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val fm = ActorMaterializer()
  import system.dispatcher

  installEventStreamLoggerFor[UnhandledMessage]

  val host = "github.com"

  fetchServerVersion1()

  //  Console.readLine()
  //  system.terminate()

  def fetchServerVersion1(): Unit = {
    println(s"Fetching HTTPS server version of host `$host` via a direct low-level connection ...")

    val connection = Http().outgoingConnectionHttps(host)
    val result = Source.single(HttpRequest()).via(connection).runWith(Sink.head)
    result.map(_.header[headers.Server]) onComplete {
      case Success(res) ⇒
        println(s"$host is running ${res mkString ", "}")
        println()
        fetchServerVersion2()

      case Failure(error) ⇒
        println(s"Error: $error")
        println()
        fetchServerVersion2()
    }
  }

  def fetchServerVersion2(): Unit = {
    println(s"Fetching HTTP server version of host `$host` via the high-level API ...")
    val result = Http().singleRequest(HttpRequest(uri = s"https://$host/"))
    result.map(_.header[headers.Server]) onComplete {
      case Success(res) ⇒
        println(s"$host is running ${res mkString ", "}")
        Http().shutdownAllConnectionPools().onComplete { _ ⇒ system.log.info("STOPPED"); shutdown() }

      case Failure(error) ⇒
        println(s"Error: $error")
        shutdown()
    }
  }

  def shutdown(): Unit = system.terminate()
}