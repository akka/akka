/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import language.implicitConversions

import scala.util.continuations._
import scala.concurrent.{ Promise, Future, ExecutionContext }
import scala.util.control.NonFatal

package object dataflow {
  /**
   * Captures a block that will be transformed into 'Continuation Passing Style' using Scala's Delimited
   * Continuations plugin.
   *
   * Within the block, the result of a Future may be accessed by calling Future.apply. At that point
   * execution is suspended with the rest of the block being stored in a continuation until the result
   * of the Future is available. If an Exception is thrown while processing, it will be contained
   * within the resulting Future.
   *
   * This allows working with Futures in an imperative style without blocking for each result.
   *
   * Completing a Future using 'Promise << Future' will also suspend execution until the
   * value of the other Future is available.
   *
   * The Delimited Continuations compiler plugin must be enabled in order to use this method.
   */
  def flow[A](body: ⇒ A @cps[Future[Any]])(implicit executor: ExecutionContext): Future[A] = {
    val p = Promise[A]
    executor.execute(
      new Runnable {
        def run = try {
          (reify(body) foreachFull (r ⇒ p.success(r).future, f ⇒ p.failure(f).future): Future[Any]) onFailure {
            case NonFatal(e) ⇒ p tryComplete Left(e)
          }
        } catch {
          case NonFatal(e) ⇒ p tryComplete Left(e)
        }
      })
    p.future
  }

  implicit class DataflowPromise[T](val promise: Promise[T]) extends AnyVal {
    final def <<(value: T): Future[T] @cps[Future[Any]] = shift {
      cont: (Future[T] ⇒ Future[Any]) ⇒ cont(promise.success(value).future)
    }

    final def <<(other: Future[T])(implicit ec: ExecutionContext): Future[T] @cps[Future[Any]] = shift {
      cont: (Future[T] ⇒ Future[Any]) ⇒
        val fr = Promise[Any]()
        (promise completeWith other).future onComplete {
          v ⇒ try { fr completeWith cont(promise.future) } catch { case NonFatal(e) ⇒ fr failure e }
        }
        fr.future
    }

    final def <<(other: Promise[T])(implicit ec: ExecutionContext): Future[T] @cps[Future[Any]] = <<(other.future)

    final def apply()(implicit ec: ExecutionContext): T @cps[Future[Any]] = shift(promise.future flatMap (_: T ⇒ Future[Any]))
  }

  implicit class DataflowFuture[T](val future: Future[T]) extends AnyVal {
    /**
     * For use only within a Future.flow block or another compatible Delimited Continuations reset block.
     *
     * Returns the result of this Future without blocking, by suspending execution and storing it as a
     * continuation until the result is available.
     */
    final def apply()(implicit ec: ExecutionContext): T @cps[Future[Any]] = shift(future flatMap (_: T ⇒ Future[Any]))
  }
}