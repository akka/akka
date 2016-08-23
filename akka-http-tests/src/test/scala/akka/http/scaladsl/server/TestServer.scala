/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.directives.Credentials
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.common.EntityStreamingSupport

import scala.concurrent.duration._
import scala.io.StdIn

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    """)

  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  import spray.json.DefaultJsonProtocol._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  final case class Tweet(message: String)
  implicit val tweetFormat = jsonFormat1(Tweet)

  implicit val jsonStreaming = EntityStreamingSupport.json()

  import ScalaXmlSupport._
  import Directives._

  def auth: AuthenticatorPF[String] = {
    case p @ Credentials.Provided(name) if p.verify(name + "-password") ⇒ name
  }

  // format: OFF
  val routes = {
    get {
      path("") {
        withRequestTimeout(1.milli, _ ⇒ HttpResponse(
          StatusCodes.EnhanceYourCalm,
          entity = "Unable to serve response within time limit, please enchance your calm.")) {
          Thread.sleep(1000)
          complete(index)
        }
      } ~
      path("secure") {
        authenticateBasicPF("My very secure site", auth) { user ⇒
          complete(<html> <body> Hello <b>{user}</b>. Access has been granted! </body> </html>)
        }
      } ~
      path("ping") {
        complete("PONG!")
      } ~
      path("crash") {
        complete(sys.error("BOOM!"))
      } ~
      path("tweet") {
        complete(Tweet("Hello, world!"))
      } ~
      (path("tweets") & parameter('n.as[Int])) { n => 
        get {
          val tweets = Source.repeat(Tweet("Hello, world!")).take(n)
          complete(tweets)
        } ~
        post {
          entity(asSourceOf[Tweet]) { tweets ⇒
            onComplete(tweets.runFold(0)({ case (acc, t) => acc + 1 })) { count => 
              complete(s"Total tweets received: " + count)
            }
          }
        } ~
        put {
          // checking the alternative syntax also works:
          entity(as[Source[Tweet, NotUsed]]) { tweets ⇒
            onComplete(tweets.runFold(0)({ case (acc, t) => acc + 1 })) { count => 
              complete(s"Total tweets received: " + count)
            }
          }
        }
      }
    } ~ 
    pathPrefix("inner")(getFromResourceDirectory("someDir"))
  }
  // format: ON

  val bindingFuture = Http().bindAndHandle(routes, interface = "0.0.0.0", port = 8080)

  println(s"Server online at http://0.0.0.0:8080/\nPress RETURN to stop...")
  StdIn.readLine()

  bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ system.terminate())

  lazy val index =
    <html>
      <body>
        <h1>Say hello to <i>akka-http-core</i>!</h1>
        <p>Defined resources:</p>
        <ul>
          <li><a href="/ping">/ping</a></li>
          <li><a href="/secure">/secure</a> Use any username and '&lt;username&gt;-password' as credentials</li>
          <li><a href="/crash">/crash</a></li>
        </ul>
      </body>
    </html>
}
