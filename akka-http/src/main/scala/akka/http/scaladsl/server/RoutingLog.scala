/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.event.LoggingAdapter
import akka.actor.{ ActorSystem, ActorContext }
import akka.http.scaladsl.model.HttpRequest

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