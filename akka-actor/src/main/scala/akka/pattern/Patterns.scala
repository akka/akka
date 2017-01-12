/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.pattern

import akka.actor.{ ActorSelection, Scheduler }
import java.util.concurrent.{ Callable, TimeUnit }
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._

object Patterns {
  import akka.japi
  import akka.actor.{ ActorRef }
  import akka.pattern.{ ask ⇒ scalaAsk, pipe ⇒ scalaPipe, gracefulStop ⇒ scalaGracefulStop, after ⇒ scalaAfter }
  import akka.util.Timeout
  import scala.concurrent.Future
  import scala.concurrent.duration._

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
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
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final Future<Object> f = Patterns.ask(
   *   worker,
   *   new akka.japi.Function<ActorRef, Object> {
   *     Object apply(ActorRef askSender) {
   *       return new Request(askSender);
   *     }
   *   },
   *   timeout);
   * }}}
   */
  def ask(actor: ActorRef, messageFactory: japi.Function[ActorRef, Any], timeout: Timeout): Future[AnyRef] = scalaAsk(actor, messageFactory.apply _)(timeout).asInstanceOf[Future[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
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
  def ask(actor: ActorRef, message: Any, timeoutMillis: Long): Future[AnyRef] =
    scalaAsk(actor, message)(new Timeout(timeoutMillis, TimeUnit.MILLISECONDS)).asInstanceOf[Future[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final Future<Object> f = Patterns.ask(
   *   worker,
   *   new akka.japi.Function<ActorRef, Object> {
   *     Object apply(ActorRef askSender) {
   *       return new Request(askSender);
   *     }
   *   },
   *   timeout);
   * }}}
   */
  def ask(actor: ActorRef, messageFactory: japi.Function[ActorRef, Any], timeoutMillis: Long): Future[AnyRef] = scalaAsk(actor, messageFactory.apply _)(Timeout(timeoutMillis.millis)).asInstanceOf[Future[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
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
   *   final Future<Object> f = Patterns.ask(selection, request, timeout);
   *   f.onSuccess(new Procedure<Object>() {
   *     public void apply(Object o) {
   *       nextActor.tell(new EnrichedResult(request, o));
   *     }
   *   });
   * }}}
   */
  def ask(selection: ActorSelection, message: Any, timeout: Timeout): Future[AnyRef] =
    scalaAsk(selection, message)(timeout).asInstanceOf[Future[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
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
   *   final Future<Object> f = Patterns.ask(selection, request, timeout);
   *   f.onSuccess(new Procedure<Object>() {
   *     public void apply(Object o) {
   *       nextActor.tell(new EnrichedResult(request, o));
   *     }
   *   });
   * }}}
   */
  def ask(selection: ActorSelection, message: Any, timeoutMillis: Long): Future[AnyRef] =
    scalaAsk(selection, message)(new Timeout(timeoutMillis, TimeUnit.MILLISECONDS)).asInstanceOf[Future[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final Future<Object> f = Patterns.ask(
   *   selection,
   *   new akka.japi.Function<ActorRef, Object> {
   *     Object apply(ActorRef askSender) {
   *       return new Request(askSender);
   *     }
   *   },
   *   timeout);
   * }}}
   */
  def ask(selection: ActorSelection, messageFactory: japi.Function[ActorRef, Any], timeoutMillis: Long): Future[AnyRef] = scalaAsk(selection, messageFactory.apply _)(Timeout(timeoutMillis.millis)).asInstanceOf[Future[AnyRef]]

  /**
   * Register an onComplete callback on this [[scala.concurrent.Future]] to send
   * the result to the given [[akka.actor.ActorRef]] or [[akka.actor.ActorSelection]].
   * Returns the original Future to allow method chaining.
   * If the future was completed with failure it is sent as a [[akka.actor.Status.Failure]]
   * to the recipient.
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
  def pipe[T](future: Future[T], context: ExecutionContext): PipeableFuture[T] = scalaPipe(future)(context)

  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with success (value `true`) when
   * existing messages of the target actor has been processed and the actor has been
   * terminated.
   *
   * Useful when you need to wait for termination or compose ordered termination of several actors.
   *
   * If the target actor isn't terminated within the timeout the [[scala.concurrent.Future]]
   * is completed with failure [[akka.pattern.AskTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: FiniteDuration): Future[java.lang.Boolean] =
    scalaGracefulStop(target, timeout).asInstanceOf[Future[java.lang.Boolean]]

  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with success (value `true`) when
   * existing messages of the target actor has been processed and the actor has been
   * terminated.
   *
   * Useful when you need to wait for termination or compose ordered termination of several actors.
   *
   * If you want to invoke specialized stopping logic on your target actor instead of PoisonPill, you can pass your
   * stop command as `stopMessage` parameter
   *
   * If the target actor isn't terminated within the timeout the [[scala.concurrent.Future]]
   * is completed with failure [[akka.pattern.AskTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: FiniteDuration, stopMessage: Any): Future[java.lang.Boolean] =
    scalaGracefulStop(target, timeout, stopMessage).asInstanceOf[Future[java.lang.Boolean]]

  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  def after[T](duration: FiniteDuration, scheduler: Scheduler, context: ExecutionContext, value: Callable[Future[T]]): Future[T] =
    scalaAfter(duration, scheduler)(value.call())(context)

  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  def after[T](duration: FiniteDuration, scheduler: Scheduler, context: ExecutionContext, value: Future[T]): Future[T] =
    scalaAfter(duration, scheduler)(value)(context)
}

object PatternsCS {
  import akka.japi
  import akka.actor.{ ActorRef }
  import akka.pattern.{ ask ⇒ scalaAsk, gracefulStop ⇒ scalaGracefulStop }
  import akka.util.Timeout
  import scala.concurrent.duration._

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The CompletionStage
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
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
   *   final CompletionStage<Object> f = Patterns.ask(worker, request, timeout);
   *   f.onSuccess(new Procedure<Object>() {
   *     public void apply(Object o) {
   *       nextActor.tell(new EnrichedResult(request, o));
   *     }
   *   });
   * }}}
   */
  def ask(actor: ActorRef, message: Any, timeout: Timeout): CompletionStage[AnyRef] =
    scalaAsk(actor, message)(timeout).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = Patterns.ask(
   *   worker,
   *   new akka.japi.Function<ActorRef, Object> {
   *     Object apply(ActorRef askSender) {
   *       return new Request(askSender);
   *     }
   *   },
   *   timeout);
   * }}}
   */
  def ask(actor: ActorRef, messageFactory: japi.Function[ActorRef, Any], timeout: Timeout): CompletionStage[AnyRef] =
    scalaAsk(actor, messageFactory.apply _)(timeout).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The CompletionStage
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
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
   *   final CompletionStage<Object> f = Patterns.ask(worker, request, timeout);
   *   f.onSuccess(new Procedure<Object>() {
   *     public void apply(Object o) {
   *       nextActor.tell(new EnrichedResult(request, o));
   *     }
   *   });
   * }}}
   */
  def ask(actor: ActorRef, message: Any, timeoutMillis: Long): CompletionStage[AnyRef] =
    scalaAsk(actor, message)(new Timeout(timeoutMillis, TimeUnit.MILLISECONDS)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = Patterns.ask(
   *   worker,
   *   new akka.japi.Function<ActorRef, Object> {
   *     Object apply(ActorRef askSender) {
   *       return new Request(askSender);
   *     }
   *   },
   *   timeout);
   * }}}
   */
  def ask(actor: ActorRef, messageFactory: japi.Function[ActorRef, Any], timeoutMillis: Long): CompletionStage[AnyRef] =
    scalaAsk(actor, messageFactory.apply _)(Timeout(timeoutMillis.millis)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided. The CompletionStage
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
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
   *   final CompletionStage<Object> f = Patterns.ask(selection, request, timeout);
   *   f.onSuccess(new Procedure<Object>() {
   *     public void apply(Object o) {
   *       nextActor.tell(new EnrichedResult(request, o));
   *     }
   *   });
   * }}}
   */
  def ask(selection: ActorSelection, message: Any, timeout: Timeout): CompletionStage[AnyRef] =
    scalaAsk(selection, message)(timeout).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided. The CompletionStage
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
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
   *   final CompletionStage<Object> f = Patterns.ask(selection, request, timeout);
   *   f.onSuccess(new Procedure<Object>() {
   *     public void apply(Object o) {
   *       nextActor.tell(new EnrichedResult(request, o));
   *     }
   *   });
   * }}}
   */
  def ask(selection: ActorSelection, message: Any, timeoutMillis: Long): CompletionStage[AnyRef] =
    scalaAsk(selection, message)(new Timeout(timeoutMillis, TimeUnit.MILLISECONDS)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = Patterns.ask(
   *   selection,
   *   new akka.japi.Function<ActorRef, Object> {
   *     Object apply(ActorRef askSender) {
   *       return new Request(askSender);
   *     }
   *   },
   *   timeout);
   * }}}
   */
  def ask(selection: ActorSelection, messageFactory: japi.Function[ActorRef, Any], timeoutMillis: Long): CompletionStage[AnyRef] =
    scalaAsk(selection, messageFactory.apply _)(Timeout(timeoutMillis.millis)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * Register an onComplete callback on this [[java.util.concurrent.CompletionStage]] to send
   * the result to the given [[akka.actor.ActorRef]] or [[akka.actor.ActorSelection]].
   * Returns the original CompletionStage to allow method chaining.
   * If the future was completed with failure it is sent as a [[akka.actor.Status.Failure]]
   * to the recipient.
   *
   * <b>Recommended usage example:</b>
   *
   * {{{
   *   final CompletionStage<Object> f = Patterns.ask(worker, request, timeout);
   *   // apply some transformation (i.e. enrich with request info)
   *   final CompletionStage<Object> transformed = f.map(new akka.japi.Function<Object, Object>() { ... });
   *   // send it on to the next stage
   *   Patterns.pipe(transformed).to(nextActor);
   * }}}
   */
  def pipe[T](future: CompletionStage[T], context: ExecutionContext): PipeableCompletionStage[T] = pipeCompletionStage(future)(context)

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with success (value `true`) when
   * existing messages of the target actor has been processed and the actor has been
   * terminated.
   *
   * Useful when you need to wait for termination or compose ordered termination of several actors.
   *
   * If the target actor isn't terminated within the timeout the [[java.util.concurrent.CompletionStage]]
   * is completed with failure [[akka.pattern.AskTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: FiniteDuration): CompletionStage[java.lang.Boolean] =
    scalaGracefulStop(target, timeout).toJava.asInstanceOf[CompletionStage[java.lang.Boolean]]

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with success (value `true`) when
   * existing messages of the target actor has been processed and the actor has been
   * terminated.
   *
   * Useful when you need to wait for termination or compose ordered termination of several actors.
   *
   * If you want to invoke specialized stopping logic on your target actor instead of PoisonPill, you can pass your
   * stop command as `stopMessage` parameter
   *
   * If the target actor isn't terminated within the timeout the [[java.util.concurrent.CompletionStage]]
   * is completed with failure [[akka.pattern.AskTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: FiniteDuration, stopMessage: Any): CompletionStage[java.lang.Boolean] =
    scalaGracefulStop(target, timeout, stopMessage).toJava.asInstanceOf[CompletionStage[java.lang.Boolean]]

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  def after[T](duration: FiniteDuration, scheduler: Scheduler, context: ExecutionContext, value: Callable[CompletionStage[T]]): CompletionStage[T] =
    afterCompletionStage(duration, scheduler)(value.call())(context)

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: FiniteDuration, scheduler: Scheduler, context: ExecutionContext, value: CompletionStage[T]): CompletionStage[T] =
    afterCompletionStage(duration, scheduler)(value)(context)
}
