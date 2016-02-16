/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl

import java.security.Principal

import akka.http.impl.util.ExampleHttpContexts
import akka.http.scaladsl.model.headers.RequestClientCertificate

import scala.concurrent.duration._
import scala.concurrent.Await
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import com.typesafe.config.{ ConfigFactory, Config }
import HttpMethods._

object RenegotiatingHttpsTestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.http.server.parsing.tls-session-info-header = on
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val fm = ActorMaterializer()

  try {
    val binding = Http().bindAndHandleSync({
      case HttpRequest(GET, Uri.Path("/"), _, _, _) ⇒ index
      case req @ HttpRequest(GET, Uri.Path("/reneg"), _, _, _) ⇒
        def responseForPrincipal(principal: Principal): HttpResponse =
          HttpResponse(entity = s"Hello ${principal.getName}!")

        req.header[headers.`Tls-Session-Info`] match {
          case Some(infoHeader) if infoHeader.peerPrincipal.isDefined ⇒
            responseForPrincipal(infoHeader.peerPrincipal.get)
          case _ ⇒
            HttpResponse(headers = RequestClientCertificate(req) :: Nil)
        }
      case HttpRequest(GET, Uri.Path("/ping"), _, _, _) ⇒
        println("Got ping")
        HttpResponse(entity = "PONG!")
      case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
    }, interface = "localhost", port = 9443, connectionContext = ExampleHttpContexts.exampleServerContextWithClientTrust)

    Await.result(binding, 1.second) // throws if binding fails
    println("Server online at http://localhost:9443")
    println("Press RETURN to stop...")
    Console.readLine()
  } finally {
    system.shutdown()
  }

  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
      """|<html>
        | <body>
        |    <h1>Say hello to <i>akka-http-core</i>!</h1>
        |    <p>Defined resources:</p>
        |    <ul>
        |      <li><a href="/ping">/ping</a></li>
        |      <li><a href="/reneg">/reneg</a></li>
        |    </ul>
        |  </body>
        |</html>""".stripMargin))
}
