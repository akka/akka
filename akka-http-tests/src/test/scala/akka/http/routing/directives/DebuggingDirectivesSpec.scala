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

import akka.event.LoggingAdapter
import akka.http.util._

class DebuggingDirectivesSpec extends RoutingSpec {
  var debugMsg = ""

  def resetDebugMsg(): Unit = { debugMsg = "" }

  implicit val log = new LoggingAdapter {
    def isErrorEnabled = true
    def isWarningEnabled = true
    def isInfoEnabled = true
    def isDebugEnabled = true

    def notifyError(message: String): Unit = {}
    def notifyError(cause: Throwable, message: String): Unit = {}
    def notifyWarning(message: String): Unit = {}
    def notifyInfo(message: String): Unit = {}
    def notifyDebug(message: String): Unit = { debugMsg += message + '\n' }
  }

  "The 'logRequest' directive" should {
    "produce a proper log message for incoming requests" in {
      Get("/hello") ~> logRequest("1") { completeOk } ~> check {
        response mustEqual Ok
        debugMsg mustEqual "1: HttpRequest(HttpMethod(GET),http://example.com/hello,List(),Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1))\n"
      }
    }
  }

  "The 'logResponse' directive" should {
    "produce a proper log message for outgoing responses" in {
      resetDebugMsg()
      Get("/hello") ~> logResponse("2") { completeOk } ~> check {
        response mustEqual Ok
        debugMsg mustEqual "2: CompleteWith(HttpResponse(200 OK,List(),Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1)))\n"
      }
    }
  }

  "The 'logRequestResponse' directive" should {
    "produce proper log messages for outgoing responses, thereby showing the corresponding request" in {
      resetDebugMsg()
      Get("/hello") ~> logRequestResponse("3") { completeOk } ~> check {
        response mustEqual Ok
        debugMsg mustEqual """|3: Response for
                              |  Request : HttpRequest(HttpMethod(GET),http://example.com/hello,List(),Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1))
                              |  Response: CompleteWith(HttpResponse(200 OK,List(),Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1)))
                              |""".stripMarginWithNewline("\n")
      }
    }
  }

}