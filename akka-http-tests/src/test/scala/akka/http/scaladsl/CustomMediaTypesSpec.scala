/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.MediaType.WithFixedCharset
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._

class CustomMediaTypesSpec extends AkkaSpec with ScalaFutures
  with Directives with RequestBuilding {

  implicit val mat = ActorMaterializer()

  "Http" should {
    "allow registering custom media type" in {
      import system.dispatcher
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      //#application-custom

      // similarily in Java: `akka.http.javadsl.settings.[...]`
      import akka.http.scaladsl.settings.ParserSettings
      import akka.http.scaladsl.settings.ServerSettings

      // define custom media type:
      val utf8 = HttpCharsets.`UTF-8`
      val `application/custom`: WithFixedCharset =
        MediaType.customWithFixedCharset("application", "custom", utf8)

      // add custom media type to parser settings:
      val parserSettings = ParserSettings(system).withCustomMediaTypes(`application/custom`)
      val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

      val routes = extractRequest { r ⇒
        complete(r.entity.contentType.toString + " = " + r.entity.contentType.getClass)
      }
      val binding = Http().bindAndHandle(routes, host, port, settings = serverSettings)
      //#application-custom

      val request = Get(s"http://$host:$port/").withEntity(HttpEntity(`application/custom`, "~~example~=~value~~"))
      val response = Http().singleRequest(request).futureValue

      response.status should ===(StatusCodes.OK)
      val responseBody = response.toStrict(1.second).futureValue.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue.utf8String
      responseBody should ===("application/custom = class akka.http.scaladsl.model.ContentType$WithFixedCharset")
    }
  }
}

