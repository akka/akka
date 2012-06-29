package akka.pattern

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import scala.concurrent.util.Duration
import akka.actor._
import akka.dispatch.{ ExecutionContext, Promise, Future }

trait FutureTimeoutSupport {
  /**
   * Returns a [[akka.dispatch.Future]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: Duration, using: Scheduler)(value: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] =
    if (duration.isFinite() && duration.length < 1) value else {
      val p = Promise[T]()
      val c = using.scheduleOnce(duration) { p completeWith value }
      p onComplete { _ ⇒ c.cancel() }
      p
    }
}