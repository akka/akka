/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.actor.ActorSystem
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import java.util.concurrent.CompletionStage

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.util.ByteString

/**
 * A convenience class to derive from to get everything from HttpService and Directives into scope.
 * Implement the [[#createRoute]] method to provide the Route and then call [[#bindRoute]]
 * to start the server on the specified interface.
 */
abstract class HttpApp
  extends AllDirectives
  with HttpServiceBase {
  def createRoute(): Route

  /**
   * Starts an HTTP server on the given interface and port. Creates the route by calling the
   * user-implemented [[#createRoute]] method and uses the route to handle requests of the server.
   */
  def bindRoute(interface: String, port: Int, system: ActorSystem): CompletionStage[ServerBinding] =
    bindRoute(interface, port, createRoute(), system)
}

import akka.http.scaladsl.model.{ HttpRequest, StatusCodes, HttpResponse }
import akka.util.ByteString
import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.StdIn

object TestSingleRequest extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val url = StdIn.readLine("url? ")

  val x = Http().singleRequest(HttpRequest(uri = url))

  val res = Await.result(x, 10.seconds)

  val response = res.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String)

  println(" ------------ RESPONSE ------------")
  println(Await.result(response, 10.seconds))
  println(" -------- END OF RESPONSE ---------")

  system.terminate()
}
