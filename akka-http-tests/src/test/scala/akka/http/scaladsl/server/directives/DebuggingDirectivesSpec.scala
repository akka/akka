/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.event.LoggingAdapter
import akka.http.impl.util._

class DebuggingDirectivesSpec extends RoutingSpec {
  var debugMsg = ""

  def resetDebugMsg(): Unit = { debugMsg = "" }

  val log = new LoggingAdapter {
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
      val route =
        withLog(log)(
          logRequest("1")(
            completeOk))

      resetDebugMsg()
      Get("/hello") ~> route ~> check {
        response shouldEqual Ok
        debugMsg shouldEqual "1: HttpRequest(HttpMethod(GET),http://example.com/hello,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1))\n"
      }
    }
  }

  "The 'logResponse' directive" should {
    "produce a proper log message for outgoing responses" in {
      val route =
        withLog(log)(
          logResult("2")(
            completeOk))

      resetDebugMsg()
      Get("/hello") ~> route ~> check {
        response shouldEqual Ok
        debugMsg shouldEqual "2: Complete(HttpResponse(200 OK,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1)))\n"
      }
    }
  }

  "The 'logRequestResponse' directive" should {
    "produce proper log messages for outgoing responses, thereby showing the corresponding request" in {
      val route =
        withLog(log)(
          logRequestResult("3")(
            completeOk))

      resetDebugMsg()
      Get("/hello") ~> route ~> check {
        response shouldEqual Ok
        debugMsg shouldEqual """|3: Response for
                              |  Request : HttpRequest(HttpMethod(GET),http://example.com/hello,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1))
                              |  Response: Complete(HttpResponse(200 OK,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1)))
                              |""".stripMarginWithNewline("\n")
      }
    }
  }

}