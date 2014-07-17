/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import akka.http.model._
import headers._
import HttpMethods._
import MediaTypes._
import Uri._
import akka.http.routing._

class MiscDirectivesSpec extends RoutingSpec {

  "routes created by the concatenation operator '~'" should {
    "yield the first sub route if it succeeded" in {
      Get() ~> {
        get { complete("first") } ~ get { complete("second") }
      } ~> check { responseAs[String] mustEqual "first" }
    }
    "yield the second sub route if the first did not succeed" in {
      Get() ~> {
        post { complete("first") } ~ get { complete("second") }
      } ~> check { responseAs[String] mustEqual "second" }
    }
    "collect rejections from both sub routes" in {
      Delete() ~> {
        get { completeOk } ~ put { completeOk }
      } ~> check { rejections mustEqual Seq(MethodRejection(GET), MethodRejection(PUT)) }
    }
    "clear rejections that have already been 'overcome' by previous directives" in {
      Put() ~> {
        put { parameter('yeah) { echoComplete } } ~
          get { completeOk }
      } ~> check { rejection mustEqual MissingQueryParamRejection("yeah") }
    }
  }

  "the jsonpWithParameter directive" should {
    val jsonResponse = HttpResponse(entity = HttpEntity(`application/json`, "[1,2,3]"))
    "convert JSON responses to corresponding javascript responses according to the given JSONP parameter" in pendingUntilFixed {
      Get("/?jsonp=someFunc") ~> {
        jsonpWithParameter("jsonp") {
          complete(jsonResponse)
        }
      } ~> check { body mustEqual HttpEntity(`application/javascript`, "someFunc([1,2,3])") }
    }
    "not act on JSON responses if no jsonp parameter is present" in pendingUntilFixed {
      Get() ~> {
        jsonpWithParameter("jsonp") {
          complete(jsonResponse)
        }
      } ~> check { response.entity mustEqual jsonResponse.entity }
    }
    "not act on non-JSON responses even if a jsonp parameter is present" in pendingUntilFixed {
      Get("/?jsonp=someFunc") ~> {
        jsonpWithParameter("jsonp") {
          complete(HttpResponse(entity = HttpEntity(`text/plain`, "[1,2,3]")))
        }
      } ~> check { body mustEqual HttpEntity(`text/plain`, "[1,2,3]") }
    }
    "reject invalid / insecure callback identifiers" in pendingUntilFixed {
      Get(Uri.from(path = "/", query = Query("jsonp" -> "(function xss(x){evil()})"))) ~> {
        jsonpWithParameter("jsonp") {
          complete(HttpResponse(entity = HttpEntity(`text/plain`, "[1,2,3]")))
        }
      } ~> check { rejections mustEqual Seq(MalformedQueryParamRejection("jsonp", "Invalid JSONP callback identifier")) }
    }
  }

  "the clientIP directive" should {
    "extract from a X-Forwarded-For header" in {
      Get() ~> addHeaders(`X-Forwarded-For`("2.3.4.5"), RawHeader("x-real-ip", "1.2.3.4")) ~> {
        clientIP { echoComplete }
      } ~> check { responseAs[String] mustEqual "2.3.4.5" }
    }
    "extract from a Remote-Address header" in pendingUntilFixed /* it seems Directive.| is broken */ {
      Get() ~> addHeaders(RawHeader("x-real-ip", "1.2.3.4"), `Remote-Address`(RemoteAddress("5.6.7.8"))) ~> {
        clientIP { echoComplete }
      } ~> check { responseAs[String] mustEqual "5.6.7.8" }
    }
    "extract from a X-Real-IP header" in {
      Get() ~> addHeader(RawHeader("x-real-ip", "1.2.3.4")) ~> {
        clientIP { echoComplete }
      } ~> check { responseAs[String] mustEqual "1.2.3.4" }
    }
  }

  "The `rewriteUnmatchedPath` directive" should {
    "rewrite the unmatched path" in {
      Get("/abc") ~> {
        rewriteUnmatchedPath(_ / "def") {
          path("abc" / "def") { completeOk }
        }
      } ~> check { response mustEqual Ok }
    }
  }
}
