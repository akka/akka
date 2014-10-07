/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.concurrent.ExecutionContext
import akka.actor.{ ActorSystem, ActorContext }
import akka.event.LoggingAdapter
import akka.http.model.HttpRequest
import akka.http.Http

/**
 * Provides a ``RoutingSetup`` for a given connection.
 */
trait RoutingSetupProvider {
  def apply(connection: Http.IncomingConnection): RoutingSetup
}
object RoutingSetupProvider {
  def apply(f: Http.IncomingConnection ⇒ RoutingSetup): RoutingSetupProvider =
    new RoutingSetupProvider {
      def apply(connection: Http.IncomingConnection) = f(connection)
    }

  implicit def default(implicit setup: RoutingSetup) = RoutingSetupProvider(_ ⇒ setup)
}

/**
 * Provides all dependencies required for route execution.
 */
class RoutingSetup(
  val settings: RoutingSettings,
  val exceptionHandler: ExceptionHandler,
  val rejectionHandler: RejectionHandler,
  val executionContext: ExecutionContext,
  val routingLog: RoutingLog) {

  // enable `import setup._` to properly bring implicits in scope
  implicit def executor: ExecutionContext = executionContext
}

object RoutingSetup {
  implicit def apply(implicit routingSettings: RoutingSettings,
                     exceptionHandler: ExceptionHandler = null,
                     rejectionHandler: RejectionHandler = null,
                     executionContext: ExecutionContext,
                     routingLog: RoutingLog): RoutingSetup =
    new RoutingSetup(
      routingSettings,
      if (exceptionHandler ne null) exceptionHandler else ExceptionHandler.default(routingSettings),
      if (rejectionHandler ne null) rejectionHandler else RejectionHandler.default(executionContext),
      executionContext,
      routingLog)
}

trait RoutingLog {
  def log: LoggingAdapter
  def requestLog(request: HttpRequest): LoggingAdapter
}

object RoutingLog extends LowerPriorityRoutingLogImplicits {
  def apply(defaultLog: LoggingAdapter): RoutingLog =
    new RoutingLog {
      def log = defaultLog
      def requestLog(request: HttpRequest) = defaultLog
    }

  implicit def fromActorContext(implicit ac: ActorContext): RoutingLog = RoutingLog(ac.system.log)
}
sealed abstract class LowerPriorityRoutingLogImplicits {
  implicit def fromActorSystem(implicit system: ActorSystem): RoutingLog = RoutingLog(system.log)
}