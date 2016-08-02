/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.event.{LoggingAdapter, Logging}
import akka.event.Logging.LogLevel
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{LoggingMagnet, LogEntry, DebuggingDirectives}

//This example shows how to log client request and response time using DebuggingDirective

class DebuggingDirectiveExtendedExampleSpec extends RoutingSpec {

  "logRequestResultWithResponseTime" in {

    val THRESHOLD_VALUE = 3
    def akkaResponseTimeLoggingFunction(loggingAdapter: LoggingAdapter,
                                        requestTimestamp: Long,
                                        level: LogLevel = Logging.InfoLevel)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val responseTimestamp: Long = System.currentTimeMillis()
          val elapsedTime: Long = responseTimestamp - requestTimestamp
          val loggingString = "Logged Request:" + req.method + ":" + req.uri + ":" + resp.status + ":" + elapsedTime
          // The red and green methods are part of the scala-rainbow library for displaying the color of the request according to the 
          // response time of that request.( i.e if responseTime > THRESHOLD_VALUE then it is RED or else GREEN)
          val coloredLoggingString = if (elapsedTime > THRESHOLD_VALUE) {
            loggingString.red
          } else {
            loggingString.green
          }
          LogEntry(coloredLoggingString, level)
        case anythingElse =>
          LogEntry(s"$anythingElse", level)
      }
      entry.logTo(loggingAdapter)
    }
    def printResponseTime(log: LoggingAdapter) = {
      val requestTimestamp = System.currentTimeMillis()
      akkaResponseTimeLoggingFunction(log, requestTimestamp)
    }

    val logResponseTime = DebuggingDirectives.logRequestResult(LoggingMagnet(printResponseTime(_)))

    Get("/lessTime") ~> logResponseTime(complete("loggedWithGreen")) ~> check {
      responseAs[String] shouldEqual "loggedWithGreen"
    }
    Get("/moreTime") ~> logResponseTime(requestContext => {
      Thread.sleep(10000)
      complete("loggedWithRed").apply(requestContext)
    }) ~> check {
      responseAs[String] shouldEqual "loggedWithRed"
    }
  }

}

