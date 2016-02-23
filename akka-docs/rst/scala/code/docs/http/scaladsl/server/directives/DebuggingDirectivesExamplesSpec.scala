/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.event.Logging
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.server.directives.{ DebuggingDirectives, LogEntry, LoggingMagnet }
import docs.http.scaladsl.server.RoutingSpec

class DebuggingDirectivesExamplesSpec extends RoutingSpec {
  "logRequest-0" in {
    // different possibilities of using logRequest

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpRequest.toString
    DebuggingDirectives.logRequest("get-user")

    // marks with "get-user", log with info level, HttpRequest.toString
    DebuggingDirectives.logRequest(("get-user", Logging.InfoLevel))

    // logs just the request method at debug level
    def requestMethod(req: HttpRequest): String = req.method.toString
    DebuggingDirectives.logRequest(requestMethod _)

    // logs just the request method at info level
    def requestMethodAsInfo(req: HttpRequest): LogEntry = LogEntry(req.method.toString, Logging.InfoLevel)
    DebuggingDirectives.logRequest(requestMethodAsInfo _)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printRequestMethod(req: HttpRequest): Unit = println(req.method)
    val logRequestPrintln = DebuggingDirectives.logRequest(LoggingMagnet(_ => printRequestMethod))

    // tests:
    Get("/") ~> logRequestPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
  }
  "logRequestResult" in {
    // different possibilities of using logRequestResponse

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpRequest.toString, HttpResponse.toString
    DebuggingDirectives.logRequestResult("get-user")

    // marks with "get-user", log with info level, HttpRequest.toString, HttpResponse.toString
    DebuggingDirectives.logRequestResult(("get-user", Logging.InfoLevel))

    // logs just the request method and response status at info level
    def requestMethodAndResponseStatusAsInfo(req: HttpRequest): Any => Option[LogEntry] = {
      case res: HttpResponse => Some(LogEntry(req.method + ":" + res.status, Logging.InfoLevel))
      case _                 => None // other kind of responses
    }
    DebuggingDirectives.logRequestResult(requestMethodAndResponseStatusAsInfo _)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printRequestMethodAndResponseStatus(req: HttpRequest)(res: Any): Unit =
      println(requestMethodAndResponseStatusAsInfo(req)(res).map(_.obj.toString).getOrElse(""))
    val logRequestResultPrintln = DebuggingDirectives.logRequestResult(LoggingMagnet(_ => printRequestMethodAndResponseStatus))

    // tests:
    Get("/") ~> logRequestResultPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
  }
  "logResult" in {
    // different possibilities of using logResponse

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpResponse.toString
    DebuggingDirectives.logResult("get-user")

    // marks with "get-user", log with info level, HttpResponse.toString
    DebuggingDirectives.logResult(("get-user", Logging.InfoLevel))

    // logs just the response status at debug level
    def responseStatus(res: Any): String = res match {
      case x: HttpResponse => x.status.toString
      case _               => "unknown response part"
    }
    DebuggingDirectives.logResult(responseStatus _)

    // logs just the response status at info level
    def responseStatusAsInfo(res: Any): LogEntry = LogEntry(responseStatus(res), Logging.InfoLevel)
    DebuggingDirectives.logResult(responseStatusAsInfo _)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printResponseStatus(res: Any): Unit = println(responseStatus(res))
    val logResultPrintln = DebuggingDirectives.logResult(LoggingMagnet(_ => printResponseStatus))

    // tests:
    Get("/") ~> logResultPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
  }
}
