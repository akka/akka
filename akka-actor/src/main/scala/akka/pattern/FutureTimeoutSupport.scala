package akka.pattern

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import scala.concurrent.util.Duration
import scala.concurrent.{ ExecutionContext, Promise, Future }
import akka.actor._

trait FutureTimeoutSupport {
  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: Duration, using: Scheduler)(value: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] =
    if (duration.isFinite() && duration.length < 1) value else {
      val p = Promise[T]()
      val c = using.scheduleOnce(duration) { p completeWith value }
      val f = p.future
      f onComplete { _ ⇒ c.cancel() }
      f
    }
}