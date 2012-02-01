/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import akka.dispatch.Future
import akka.actor.{ Status, ActorRef }

trait PipeToSupport {

  final class PipeableFuture[T](val future: Future[T]) {
    def pipeTo(actorRef: ActorRef): Future[T] = akka.pattern.pipe(future, actorRef)
  }

  /**
   * Import this implicit conversion to gain the `pipeTo` method on [[akka.dispatch.Future]]:
   *
   * {{{
   * import akka.pattern.pipeTo
   *
   * Future { doExpensiveCalc() } pipeTo nextActor
   * }}}
   */
  implicit def pipeTo[T](future: Future[T]): PipeableFuture[T] = new PipeableFuture(future)

  /**
   * Register an onComplete callback on this [[akka.dispatch.Future]] to send
   * the result to the given actor reference. Returns the original Future to
   * allow method chaining.
   *
   * <b>Recommended usage example:</b>
   *
   * {{{
   *   val f = ask(worker, request)(timeout)
   *   flow {
   *     EnrichedRequest(request, f())
   *   } pipeTo nextActor
   * }}}
   *
   * [see [[akka.dispatch.Future]] for a description of `flow`]
   */
  def pipe[T](future: Future[T], recipient: ActorRef): Future[T] =
    future onComplete {
      case Right(r) ⇒ recipient ! r
      case Left(f)  ⇒ recipient ! Status.Failure(f)
    }

}