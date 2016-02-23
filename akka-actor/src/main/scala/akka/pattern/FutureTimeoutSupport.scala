/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.pattern

import scala.concurrent.{ ExecutionContext, Promise, Future }
import akka.actor._
import scala.util.control.NonFatal
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import akka.dispatch.Futures
import java.util.function.BiConsumer

trait FutureTimeoutSupport {
  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: FiniteDuration, using: Scheduler)(value: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] =
    if (duration.isFinite() && duration.length < 1) {
      try value catch { case NonFatal(t) ⇒ Future.failed(t) }
    } else {
      val p = Promise[T]()
      using.scheduleOnce(duration) { p completeWith { try value catch { case NonFatal(t) ⇒ Future.failed(t) } } }
      p.future
    }

  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def afterCompletionStage[T](duration: FiniteDuration, using: Scheduler)(value: ⇒ CompletionStage[T])(implicit ec: ExecutionContext): CompletionStage[T] =
    if (duration.isFinite() && duration.length < 1) {
      try value catch { case NonFatal(t) ⇒ Futures.failedCompletionStage(t) }
    } else {
      val p = new CompletableFuture[T]
      using.scheduleOnce(duration) {
        try {
          val future = value
          future.whenComplete(new BiConsumer[T, Throwable] {
            override def accept(t: T, ex: Throwable): Unit = {
              if (t != null) p.complete(t)
              if (ex != null) p.completeExceptionally(ex)
            }
          })
        } catch {
          case NonFatal(ex) ⇒ p.completeExceptionally(ex)
        }
      }
      p
    }
}
