/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.HttpProtocols.`HTTP/1.0`
import akka.http.scaladsl.model.MediaType.WithFixedCharset
import akka.http.scaladsl.model.RequestEntityAcceptance.Expected
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class CustomStatusCodesSpec extends AkkaSpec with ScalaFutures
  with Directives with RequestBuilding {

  implicit val mat = ActorMaterializer()

  "Http" should {
    "allow registering custom status code" in {
      import system.dispatcher
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      //#application-custom
      // similarily in Java: `akka.http.javadsl.settings.[...]`
      import akka.http.scaladsl.settings.{ ParserSettings, ServerSettings }

      // define custom status code:
      val LeetCode = StatusCodes.custom(777, "LeetCode", "Some reason", isSuccess = true, allowsEntity = false)

      // add custom method to parser settings:
      val parserSettings = ParserSettings(system).withCustomStatusCodes(LeetCode)
      val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

      val clientConSettings = ClientConnectionSettings(system).withParserSettings(parserSettings)
      val clientSettings = ConnectionPoolSettings(system).withConnectionSettings(clientConSettings)

      val routes =
        complete(HttpResponse(status = LeetCode))

      // use serverSettings in server:
      val binding = Http().bindAndHandle(routes, host, port, settings = serverSettings)

      // use clientSettings in client:
      val request = HttpRequest(uri = s"http://$host:$port/")
      val response = Http().singleRequest(request, settings = clientSettings).futureValue

      response.status should ===(LeetCode)
      binding.foreach(_.unbind())
      //#application-custom
    }
  }

}

