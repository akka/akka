/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import akka.actor.Scheduler
import akka.dispatch.ExecutionContext
import java.util.concurrent.Callable

object Patterns {
  import akka.actor.{ ActorRef, ActorSystem }
  import akka.dispatch.Future
  import akka.pattern.{ ask ⇒ scalaAsk, pipe ⇒ scalaPipe, gracefulStop ⇒ scalaGracefulStop, after ⇒ scalaAfter }
  import akka.util.{ Timeout, Duration }

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[akka.dispatch.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.actor.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`).
   *
   * <b>Warning:</b>
   * When using future callbacks, inside actors you need to carefully avoid closing over
   * the containing actor’s object, i.e. do not call methods or access mutable state
   * on the enclosing actor from within the callback. This would break the actor
   * encapsulation and may introduce synchronization bugs and race conditions because
   * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
   * there is not yet a way to detect these illegal accesses at compile time.
   *
   * <b>Recommended usage:</b>
   *
   * {{{
   *   final Future<Object> f = Patterns.ask(worker, request, timeout);
   *   f.onSuccess(new Procedure<Object>() {
   *     public void apply(Object o) {
   *       nextActor.tell(new EnrichedResult(request, o));
   *     }
   *   });
   * }}}
   */
  def ask(actor: ActorRef, message: Any, timeout: Timeout): Future[AnyRef] = scalaAsk(actor, message)(timeout).asInstanceOf[Future[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[akka.dispatch.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.actor.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`).
   *
   * <b>Warning:</b>
   * When using future callbacks, inside actors you need to carefully avoid closing over
   * the containing actor’s object, i.e. do not call methods or access mutable state
   * on the enclosing actor from within the callback. This would break the actor
   * encapsulation and may introduce synchronization bugs and race conditions because
   * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
   * there is not yet a way to detect these illegal accesses at compile time.
   *
   * <b>Recommended usage:</b>
   *
   * {{{
   *   final Future<Object> f = Patterns.ask(worker, request, timeout);
   *   f.onSuccess(new Procedure<Object>() {
   *     public void apply(Object o) {
   *       nextActor.tell(new EnrichedResult(request, o));
   *     }
   *   });
   * }}}
   */
  def ask(actor: ActorRef, message: Any, timeoutMillis: Long): Future[AnyRef] = scalaAsk(actor, message)(new Timeout(timeoutMillis)).asInstanceOf[Future[AnyRef]]

  /**
   * Register an onComplete callback on this [[akka.dispatch.Future]] to send
   * the result to the given actor reference. Returns the original Future to
   * allow method chaining.
   *
   * <b>Recommended usage example:</b>
   *
   * {{{
   *   final Future<Object> f = Patterns.ask(worker, request, timeout);
   *   // apply some transformation (i.e. enrich with request info)
   *   final Future<Object> transformed = f.map(new akka.japi.Function<Object, Object>() { ... });
   *   // send it on to the next stage
   *   Patterns.pipe(transformed).to(nextActor);
   * }}}
   */
  def pipe[T](future: Future[T]): PipeableFuture[T] = scalaPipe(future)

  /**
   * Returns a [[akka.dispatch.Future]] that will be completed with success (value `true`) when
   * existing messages of the target actor has been processed and the actor has been
   * terminated.
   *
   * Useful when you need to wait for termination or compose ordered termination of several actors.
   *
   * If the target actor isn't terminated within the timeout the [[akka.dispatch.Future]]
   * is completed with failure [[akka.actor.ActorTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: Duration, system: ActorSystem): Future[java.lang.Boolean] =
    scalaGracefulStop(target, timeout)(system).asInstanceOf[Future[java.lang.Boolean]]

  /**
   * Returns a [[akka.dispatch.Future]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  def after[T](duration: Duration, scheduler: Scheduler, context: ExecutionContext, value: Callable[Future[T]]): Future[T] =
    scalaAfter(duration, scheduler)(value.call())(context)

  /**
   * Returns a [[akka.dispatch.Future]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: Duration, scheduler: Scheduler, context: ExecutionContext, value: Future[T]): Future[T] =
    scalaAfter(duration, scheduler)(value)(context)
}
