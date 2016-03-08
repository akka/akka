/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model.{ StatusCodes, HttpResponse }
import akka.http.scaladsl.server.directives.Credentials
import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import scala.concurrent.duration._

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  import ScalaXmlSupport._
  import Directives._

  def auth: AuthenticatorPF[String] = {
    case p @ Credentials.Provided(name) if p.verify(name + "-password") ⇒ name
  }

  val bindingFuture = Http().bindAndHandle({
    get {
      path("") {
        withRequestTimeout(1.milli, _ ⇒ HttpResponse(StatusCodes.EnhanceYourCalm,
          entity = "Unable to serve response within time limit, please enchance your calm.")) {
          Thread.sleep(1000)
          complete(index)
        }
      } ~
        path("secure") {
          authenticateBasicPF("My very secure site", auth) { user ⇒
            complete(<html><body>Hello <b>{ user }</b>. Access has been granted!</body></html>)
          }
        } ~
        path("ping") {
          complete("PONG!")
        } ~
        path("crash") {
          complete(sys.error("BOOM!"))
        }
    } ~ pathPrefix("inner")(getFromResourceDirectory("someDir"))
  }, interface = "localhost", port = 8080)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine()

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
