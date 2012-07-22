package akka.pattern

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import scala.concurrent.util.Duration
import scala.concurrent.{ ExecutionContext, Promise, Future }
import akka.actor._
import scala.util.control.NonFatal

trait FutureTimeoutSupport {
  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: Duration, using: Scheduler)(value: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] =
    if (duration.isFinite() && duration.length < 1) {
      try value catch { case NonFatal(t) ⇒ Future.failed(t) }
    } else {
      val p = Promise[T]()
      using.scheduleOnce(duration) { p completeWith { try value catch { case NonFatal(t) ⇒ Future.failed(t) } } }
      p.future
    }
}