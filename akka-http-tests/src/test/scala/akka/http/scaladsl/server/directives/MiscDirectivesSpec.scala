/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import headers._
import HttpMethods._
import MediaTypes._
import Uri._

class MiscDirectivesSpec extends RoutingSpec {

  "the extractClientIP directive" should {
    "extract from a X-Forwarded-For header" in {
      Get() ~> addHeaders(`X-Forwarded-For`("2.3.4.5"), RawHeader("x-real-ip", "1.2.3.4")) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "2.3.4.5" }
    }
    "extract from a Remote-Address header" in {
      Get() ~> addHeaders(RawHeader("x-real-ip", "1.2.3.4"), `Remote-Address`(RemoteAddress("5.6.7.8"))) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "5.6.7.8" }
    }
    "extract from a X-Real-IP header" in {
      Get() ~> addHeader(RawHeader("x-real-ip", "1.2.3.4")) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "1.2.3.4" }
    }
  }
}
