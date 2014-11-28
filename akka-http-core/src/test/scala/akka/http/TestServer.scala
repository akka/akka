/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.actor.ActorSystem
import akka.http.model._
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Flow, Sink }
import com.typesafe.config.{ ConfigFactory, Config }
import HttpMethods._
import scala.concurrent.Await
import scala.concurrent.duration._

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)

  val requestHandler: HttpRequest ⇒ HttpResponse = {
    case HttpRequest(GET, Uri.Path("/"), _, _, _)      ⇒ index
    case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
    case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
    case _: HttpRequest                                ⇒ HttpResponse(404, entity = "Unknown resource!")
  }

  implicit val materializer = FlowMaterializer()

  val Http.ServerSource(source, key) = Http(system).bind(interface = "localhost", port = 8080)
  val materializedMap = source.to(Sink.foreach {
    case Http.IncomingConnection(remoteAddress, flow) ⇒
      println("Accepted new connection from " + remoteAddress)
      flow.join(Flow[HttpRequest].map(requestHandler)).run()
  }).run()

  val serverBinding = Await.result(materializedMap.get(key), 3 seconds)

  println(s"Server online at http://${serverBinding.localAddress.getHostName}:${serverBinding.localAddress.getPort}")
  println("Press RETURN to stop...")
  Console.readLine()

  serverBinding.close()
  system.shutdown()

  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(MediaTypes.`text/html`,
      """|<html>
         | <body>
         |    <h1>Say hello to <i>akka-http-core</i>!</h1>
         |    <p>Defined resources:</p>
         |    <ul>
         |      <li><a href="/ping">/ping</a></li>
         |      <li><a href="/crash">/crash</a></li>
         |    </ul>
         |  </body>
         |</html>""".stripMargin))
}
