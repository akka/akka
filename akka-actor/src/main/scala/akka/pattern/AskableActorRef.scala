/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import akka.actor.{ ActorRef, InternalActorRef, AskTimeoutException }
import akka.dispatch.{ Future, Promise }
import akka.util.Timeout

final class AskableActorRef(val actorRef: ActorRef) {

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
   *   val f = worker.ask(request)(timeout)
   *   flow {
   *     EnrichedRequest(request, f())
   *   } pipeTo nextActor
   * }}}
   *
   * [see the [[akka.dispatch.Future]] companion object for a description of `flow`]
   */
  def ask(message: AnyRef)(implicit timeout: Timeout): Future[Any] = akka.pattern.ask(actorRef, message)(timeout)

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
   *   val f = worker ? request
   *   flow {
   *     EnrichedRequest(request, f())
   *   } pipeTo nextActor
   * }}}
   *
   * [see the [[akka.dispatch.Future]] companion object for a description of `flow`]
   */
  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = akka.pattern.ask(actorRef, message)

  /*
   * FIXME: I think this should be removed, since it introduces an “ambiguity” 
   * when sending Tuple2, which the compiler resolves unexpectedly to this 
   * method; also overloading is bad, isn’t it?  - RK (ticket #1653)
   */
  /**
   * Sends a message asynchronously, returning a future which may eventually hold the reply.
   * The implicit parameter with the default value is just there to disambiguate it from the version that takes the
   * implicit timeout
   */
  def ?(message: Any, timeout: Timeout)(implicit ignore: Int = 0): Future[Any] = ?(message)(timeout)

}
