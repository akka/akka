/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.event.{ Logging, LoggingAdapter }
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.RouteResult.Rejected

class DebuggingDirectivesSpec extends RoutingSpec {
  var debugMsg = ""

  def resetDebugMsg(): Unit = { debugMsg = "" }

  // Gracefully handle prefix difference for `ByteString.ByteString1C`
  // in Scala 2.12 due to https://issues.scala-lang.org/browse/SI-9019.
  def normalizedDebugMsg(): String =
    debugMsg.replace("ByteString.ByteString1C(", "ByteString(")

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
        normalizedDebugMsg shouldEqual "1: HttpRequest(HttpMethod(GET),http://example.com/hello,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1))\n"
      }
    }
  }

  "The 'logResult' directive" should {
    "produce a proper log message for outgoing responses" in {
      val route =
        withLog(log)(
          logResult("2")(
            completeOk))

      resetDebugMsg()
      Get("/hello") ~> route ~> check {
        response shouldEqual Ok
        normalizedDebugMsg shouldEqual "2: Complete(HttpResponse(200 OK,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1)))\n"
      }
    }
  }

  "The 'logRequestResult' directive" should {
    "produce proper log messages for outgoing responses, thereby showing the corresponding request" in {
      val route =
        withLog(log)(
          logRequestResult("3")(
            completeOk))

      resetDebugMsg()
      Get("/hello") ~> route ~> check {
        response shouldEqual Ok
        normalizedDebugMsg shouldEqual
          """|3: Response for
             |  Request : HttpRequest(HttpMethod(GET),http://example.com/hello,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1))
             |  Response: Complete(HttpResponse(200 OK,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1)))
             |""".stripMarginWithNewline("\n")
      }
    }
    "be able to log only rejections" in {
      val rejectionLogger: HttpRequest ⇒ RouteResult ⇒ Option[LogEntry] = req ⇒ {
        case Rejected(rejections) ⇒ Some(LogEntry(s"Request: $req\nwas rejected with rejections:\n$rejections", Logging.DebugLevel))
        case _                    ⇒ None
      }

      val route =
        withLog(log)(
          logRequestResult(rejectionLogger)(
            reject(ValidationRejection("The request could not be validated"))))

      resetDebugMsg()
      Get("/hello") ~> route ~> check {
        handled shouldBe false
        normalizedDebugMsg shouldEqual
          """Request: HttpRequest(HttpMethod(GET),http://example.com/hello,List(),HttpEntity.Strict(none/none,ByteString()),HttpProtocol(HTTP/1.1))
            |was rejected with rejections:
            |List(ValidationRejection(The request could not be validated,None))
            |""".stripMargin
      }
    }
  }
}
