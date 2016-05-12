/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import akka.http.javadsl.server.ExceptionHandler
import akka.http.javadsl.server.RejectionHandler
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.directives.{ ExecutionDirectives ⇒ D }

abstract class ExecutionDirectives extends DebuggingDirectives {
  def handleExceptions(handler: ExceptionHandler, inner: java.util.function.Supplier[Route]) = RouteAdapter(
    D.handleExceptions(handler.asScala) {
      inner.get.delegate
    })

  def handleRejections(handler: RejectionHandler, inner: java.util.function.Supplier[Route]) = RouteAdapter(
    D.handleRejections(handler.asScala) {
      inner.get.delegate
    })
}
