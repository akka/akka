/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import akka.http.marshallers.xml.ScalaXmlSupport
import akka.http.server.directives.AuthenticationDirectives._
import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.FlowMaterializer
import akka.util.Timeout
import akka.http.Http
import akka.http.model._

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off""")
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher
  implicit val materializer = FlowMaterializer()

  import ScalaRoutingDSL._
  import ScalaXmlSupport._

  def auth =
    HttpBasicAuthenticator.provideUserName {
      case p @ UserCredentials.Provided(name) ⇒ p.verifySecret(name + "-password")
      case _                                  ⇒ false
    }

  val binding = Http().bind(interface = "localhost", port = 8080)

  val materializedMap =
    handleConnections(binding) withRoute {
      get {
        path("") {
          complete(index)
        } ~
          path("secure") {
            HttpBasicAuthentication("My very secure site")(auth) { user ⇒
              complete(<html><body>Hello <b>{ user }</b>. Access has been granted!</body></html>)
            }
          } ~
          path("ping") {
            complete("PONG!")
          } ~
          path("crash") {
            complete(sys.error("BOOM!"))
          }
      }
    }

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine()
  binding.unbind(materializedMap).onComplete(_ ⇒ system.shutdown())

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
