/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.{ Http, TestUtils }
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.HttpProtocols._
import akka.http.scaladsl.model.RequestEntityAcceptance.Expected
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class CustomHttpMethodSpec extends AkkaSpec with ScalaFutures
  with Directives with RequestBuilding {

  implicit val mat = ActorMaterializer()

  "Http" should {
    "allow registering custom method" in {
      import system.dispatcher
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      //#application-custom
      import akka.http.scaladsl.settings.{ ParserSettings, ServerSettings }

      // define custom media type:
      val BOLT = HttpMethod.custom("BOLT", safe = false,
        idempotent = true, requestEntityAcceptance = Expected)

      // add custom method to parser settings:
      val parserSettings = ParserSettings(system).withCustomMethods(BOLT)
      val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

      val routes = extractMethod { method ⇒
        complete(s"This is a ${method.name} method request.")
      }
      val binding = Http().bindAndHandle(routes, host, port, settings = serverSettings)
      //#application-custom

      val request = HttpRequest(BOLT, s"http://$host:$port/", protocol = `HTTP/1.0`)
      val response = Http().singleRequest(request).futureValue

      response.status should ===(StatusCodes.OK)
      val responseBody = response.toStrict(1.second).futureValue.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue.utf8String
      responseBody should ===("This is a BOLT method request.")
    }
  }
}

