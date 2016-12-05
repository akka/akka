/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.io.File
import java.nio.file.Path

import com.typesafe.config.{ Config, ConfigFactory }

import scala.util.{ Failure, Success }
import akka.actor.{ ActorSystem, UnhandledMessage }
import akka.stream.{ ActorMaterializer, IOResult }
import akka.stream.scaladsl.{ FileIO, Sink, Source }
import akka.http.scaladsl.model._
import akka.http.impl.util._
import akka.util.ByteString

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

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

  // for gathering dumps of entity and headers from akka http client
  // and curl in parallel to compare
  def fetchAndStoreABunchOfUrlsWithHttpAndCurl(urls: Seq[String]): Unit = {
    assert(urls.nonEmpty)
    assert(new File("/tmp/client-dumps/").exists(), "you need to create /tmp/client-dumps/ before running")

    val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.log-dead-letters = off
    akka.io.tcp.trace-logging = off""")
    implicit val system = ActorSystem("ServerTest", testConf)
    implicit val fm = ActorMaterializer()
    import system.dispatcher

    try {
      val done = Future.traverse(urls.zipWithIndex) {
        case (url, index) ⇒
          Http().singleRequest(HttpRequest(uri = url)).map { response ⇒

            val path = new File(s"/tmp/client-dumps/akka-body-$index.dump").toPath
            val headersPath = new File(s"/tmp/client-dumps/akka-headers-$index.dump").toPath

            import scala.sys.process._
            (s"""curl -D /tmp/client-dumps/curl-headers-$index.dump $url""" #> new File(s"/tmp/client-dumps/curl-body-$index.dump")).!

            val headers = Source(response.headers).map(header ⇒ ByteString(header.name + ": " + header.value + "\n"))
              .runWith(FileIO.toPath(headersPath))

            val body = response.entity.dataBytes
              .runWith(FileIO.toPath(path))
              .map(res ⇒ (url, path, res)): Future[(String, Path, IOResult)]

            headers.flatMap(_ ⇒ body)
          }
      }

      println("Fetched urls: " + Await.result(done, 10.minutes))
    } finally {
      Http().shutdownAllConnectionPools().flatMap(_ ⇒ system.terminate())
    }
  }

  def shutdown(): Unit = system.terminate()
}
