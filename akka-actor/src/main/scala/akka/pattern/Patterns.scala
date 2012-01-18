/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

object Patterns {
  import akka.actor.ActorRef
  import akka.dispatch.Future
  import akka.pattern.{ ask ⇒ scalaAsk }
  import akka.util.Timeout

  /**
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
}
