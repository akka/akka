/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.event.{ LoggingAdapter, Logging }
import akka.event.Logging.LogLevel
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.server.RouteResult.{ Rejected, Complete }
import akka.http.scaladsl.server.directives.{ DebuggingDirectives, LogEntry, LoggingMagnet }
import docs.http.scaladsl.server.RoutingSpec

class DebuggingDirectivesExamplesSpec extends RoutingSpec {
  "logRequest-0" in {
    //#logRequest-0
    // different possibilities of using logRequest

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpRequest.toString
    DebuggingDirectives.logRequest("get-user")

    // marks with "get-user", log with info level, HttpRequest.toString
    DebuggingDirectives.logRequest(("get-user", Logging.InfoLevel))

    // logs just the request method at debug level
    def requestMethod(req: HttpRequest): String = req.method.name
    DebuggingDirectives.logRequest(requestMethod _)

    // logs just the request method at info level
    def requestMethodAsInfo(req: HttpRequest): LogEntry = LogEntry(req.method.name, Logging.InfoLevel)
    DebuggingDirectives.logRequest(requestMethodAsInfo _)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printRequestMethod(req: HttpRequest): Unit = println(req.method.name)
    val logRequestPrintln = DebuggingDirectives.logRequest(LoggingMagnet(_ => printRequestMethod))

    // tests:
    Get("/") ~> logRequestPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
    //#logRequest-0
  }
  "logRequestResult" in {
    //#logRequestResult
    // different possibilities of using logRequestResponse

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpRequest.toString, HttpResponse.toString
    DebuggingDirectives.logRequestResult("get-user")

    // marks with "get-user", log with info level, HttpRequest.toString, HttpResponse.toString
    DebuggingDirectives.logRequestResult(("get-user", Logging.InfoLevel))

    // logs just the request method and response status at info level
    def requestMethodAndResponseStatusAsInfo(req: HttpRequest): RouteResult => Option[LogEntry] = {
      case RouteResult.Complete(res) => Some(LogEntry(req.method.name + ": " + res.status, Logging.InfoLevel))
      case _                         => None // no log entries for rejections
    }
    DebuggingDirectives.logRequestResult(requestMethodAndResponseStatusAsInfo _)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printRequestMethodAndResponseStatus(req: HttpRequest)(res: RouteResult): Unit =
      println(requestMethodAndResponseStatusAsInfo(req)(res).map(_.obj.toString).getOrElse(""))
    val logRequestResultPrintln = DebuggingDirectives.logRequestResult(LoggingMagnet(_ => printRequestMethodAndResponseStatus))

    // tests:
    Get("/") ~> logRequestResultPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
    //#logRequestResult
  }
  "logResult" in {
    //#logResult
    // different possibilities of using logResponse

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpResponse.toString
    DebuggingDirectives.logResult("get-user")

    // marks with "get-user", log with info level, HttpResponse.toString
    DebuggingDirectives.logResult(("get-user", Logging.InfoLevel))

    // logs just the response status at debug level
    def responseStatus(res: RouteResult): String = res match {
      case RouteResult.Complete(x)          => x.status.toString
      case RouteResult.Rejected(rejections) => "Rejected: " + rejections.mkString(", ")
    }
    DebuggingDirectives.logResult(responseStatus _)

    // logs just the response status at info level
    def responseStatusAsInfo(res: RouteResult): LogEntry = LogEntry(responseStatus(res), Logging.InfoLevel)
    DebuggingDirectives.logResult(responseStatusAsInfo _)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printResponseStatus(res: RouteResult): Unit = println(responseStatus(res))
    val logResultPrintln = DebuggingDirectives.logResult(LoggingMagnet(_ => printResponseStatus))

    // tests:
    Get("/") ~> logResultPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
    //#logResult
  }
  "logRequestResultWithResponseTime" in {
    //#logRequestResultWithResponseTime

    def akkaResponseTimeLoggingFunction(
      loggingAdapter:   LoggingAdapter,
      requestTimestamp: Long,
      level:            LogLevel       = Logging.InfoLevel)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val responseTimestamp: Long = System.nanoTime
          val elapsedTime: Long = (responseTimestamp - requestTimestamp) / 1000000
          val loggingString = s"""Logged Request:${req.method}:${req.uri}:${resp.status}:${elapsedTime}"""
          LogEntry(loggingString, level)
        case Rejected(reason) =>
          LogEntry(s"Rejected Reason: ${reason.mkString(",")}", level)
      }
      entry.logTo(loggingAdapter)
    }
    def printResponseTime(log: LoggingAdapter) = {
      val requestTimestamp = System.nanoTime
      akkaResponseTimeLoggingFunction(log, requestTimestamp)(_)
    }

    val logResponseTime = DebuggingDirectives.logRequestResult(LoggingMagnet(printResponseTime(_)))

    Get("/") ~> logResponseTime(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
    //#logRequestResultWithResponseTime
  }
}
