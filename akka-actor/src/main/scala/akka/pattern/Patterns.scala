/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import java.util.concurrent.{ Callable, CompletionStage, TimeUnit }

import akka.actor.{ ActorSelection, Scheduler }
import akka.util.JavaDurationConverters._

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext

/**
 * Java API: for Akka patterns such as `ask`, `pipe` and others which work with [[java.util.concurrent.CompletionStage]].
 */
object Patterns {
  import akka.actor.ActorRef
  import akka.japi
  import akka.pattern.{
    after ⇒ scalaAfter,
    ask ⇒ scalaAsk,
    gracefulStop ⇒ scalaGracefulStop,
    pipe ⇒ scalaPipe,
    retry ⇒ scalaRetry
  }
  import akka.util.Timeout

  import scala.concurrent.Future
  import scala.concurrent.duration._

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided.
   *
   * The Future will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided.
   *
   * The CompletionStage will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   *   final CompletionStage<Object> f = Patterns.ask(worker, request, duration);
   *   f.thenRun(result -> nextActor.tell(new EnrichedResult(request, result)));
   * }}}
   */
  def ask(actor: ActorRef, message: Any, timeout: java.time.Duration): CompletionStage[AnyRef] =
    scalaAsk(actor, message)(timeout.asScala).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final Future<Object> f = Patterns.askWithReplyTo(
   *   worker,
   *   replyTo -> new Request(replyTo),
   *   timeout);
   * }}}
   */
  def askWithReplyTo(actor: ActorRef, messageFactory: japi.Function[ActorRef, Any], timeout: Timeout): Future[AnyRef] =
    extended.ask(actor, messageFactory.apply _)(timeout).asInstanceOf[Future[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = Patterns.askWithReplyTo(
   *   worker,
   *   askSender -> new Request(askSender),
   *   timeout);
   * }}}
   *
   * @param actor          the actor to be asked
   * @param messageFactory function taking an actor ref and returning the message to be sent
   * @param timeout        the timeout for the response before failing the returned completion stage
   */
  def askWithReplyTo(actor: ActorRef, messageFactory: japi.function.Function[ActorRef, Any], timeout: java.time.Duration): CompletionStage[AnyRef] =
    extended.ask(actor, messageFactory.apply _)(Timeout.create(timeout)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided.
   *
   * The Future will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   * final Future<Object> f = Patterns.askWithReplyTo(
   *   worker,
   *   replyTo -> new Request(replyTo),
   *   timeout);
   * }}}
   */
  def askWithReplyTo(actor: ActorRef, messageFactory: japi.Function[ActorRef, Any], timeoutMillis: Long): Future[AnyRef] =
    extended.ask(actor, messageFactory.apply _)(Timeout(timeoutMillis.millis)).asInstanceOf[Future[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided.
   *
   * The Future will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided.
   *
   * The CompletionStage will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   *   final CompletionStage<Object> f = Patterns.ask(selection, request, duration);
   *   f.thenRun(result -> nextActor.tell(new EnrichedResult(request, result)));
   * }}}
   */
  def ask(selection: ActorSelection, message: Any, timeout: java.time.Duration): CompletionStage[AnyRef] =
    scalaAsk(selection, message)(timeout.asScala).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[scala.concurrent.Future]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided.
   *
   * The Future will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   * final Future<Object> f = Patterns.askWithReplyTo(
   *   selection,
   *   replyTo -> new Request(replyTo),
   *   timeout);
   * }}}
   */
  def askWithReplyTo(selection: ActorSelection, messageFactory: japi.Function[ActorRef, Any], timeoutMillis: Long): Future[AnyRef] =
    extended.ask(selection, messageFactory.apply _)(Timeout(timeoutMillis.millis)).asInstanceOf[Future[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = Patterns.askWithReplyTo(
   *   selection,
   *   replyTo -> new Request(replyTo),
   *   timeout);
   * }}}
   */
  def askWithReplyTo(selection: ActorSelection, messageFactory: japi.Function[ActorRef, Any], timeout: java.time.Duration): CompletionStage[AnyRef] =
    extended.ask(selection, messageFactory.apply _)(timeout.asScala).toJava.asInstanceOf[CompletionStage[AnyRef]]

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
   *   // send it on to the next operator
   *   Patterns.pipe(transformed, context).to(nextActor);
   * }}}
   */
  def pipe[T](future: Future[T], context: ExecutionContext): PipeableFuture[T] = scalaPipe(future)(context)

  /**
   * When this [[java.util.concurrent.CompletionStage]] finishes, send its result to the given
   * [[akka.actor.ActorRef]] or [[akka.actor.ActorSelection]].
   * Returns the original CompletionStage to allow method chaining.
   * If the future was completed with failure it is sent as a [[akka.actor.Status.Failure]]
   * to the recipient.
   *
   * <b>Recommended usage example:</b>
   *
   * {{{
   *   final CompletionStage<Object> f = Patterns.ask(worker, request, timeout);
   *   // apply some transformation (i.e. enrich with request info)
   *   final CompletionStage<Object> transformed = f.thenApply(result -> { ... });
   *   // send it on to the next operator
   *   Patterns.pipe(transformed, context).to(nextActor);
   * }}}
   */
  def pipe[T](future: CompletionStage[T], context: ExecutionContext): PipeableCompletionStage[T] = pipeCompletionStage(future)(context)

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
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with success (value `true`) when
   * existing messages of the target actor has been processed and the actor has been
   * terminated.
   *
   * Useful when you need to wait for termination or compose ordered termination of several actors.
   *
   * If the target actor isn't terminated within the timeout the [[java.util.concurrent.CompletionStage]]
   * is completed with failure [[akka.pattern.AskTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: java.time.Duration): CompletionStage[java.lang.Boolean] =
    scalaGracefulStop(target, timeout.asScala).toJava.asInstanceOf[CompletionStage[java.lang.Boolean]]

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
  def gracefulStop(target: ActorRef, timeout: java.time.Duration, stopMessage: Any): CompletionStage[java.lang.Boolean] =
    scalaGracefulStop(target, timeout.asScala, stopMessage).toJava.asInstanceOf[CompletionStage[java.lang.Boolean]]

  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  def after[T](duration: FiniteDuration, scheduler: Scheduler, context: ExecutionContext, value: Callable[Future[T]]): Future[T] =
    scalaAfter(duration, scheduler)(value.call())(context)

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  def after[T](duration: java.time.Duration, scheduler: Scheduler, context: ExecutionContext, value: Callable[CompletionStage[T]]): CompletionStage[T] =
    afterCompletionStage(duration.asScala, scheduler)(value.call())(context)

  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  def after[T](duration: FiniteDuration, scheduler: Scheduler, context: ExecutionContext, value: Future[T]): Future[T] =
    scalaAfter(duration, scheduler)(value)(context)

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: java.time.Duration, scheduler: Scheduler, context: ExecutionContext, value: CompletionStage[T]): CompletionStage[T] =
    afterCompletionStage(duration.asScala, scheduler)(value)(context)

  /**
   * Returns an internally retrying [[scala.concurrent.Future]]
   * The first attempt will be made immediately, and each subsequent attempt will be made after 'delay'.
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry
   * If attempts are exhausted the returned future is simply the result of invoking attempt.
   * Note that the attempt function will be invoked on the given execution context for subsequent tries and
   * therefore must be thread safe (not touch unsafe mutable state).
   */
  def retry[T](attempt: Callable[Future[T]], attempts: Int, delay: FiniteDuration, scheduler: Scheduler,
               context: ExecutionContext): Future[T] =
    scalaRetry(() ⇒ attempt.call, attempts, delay)(context, scheduler)

  /**
   * Returns an internally retrying [[java.util.concurrent.CompletionStage]]
   * The first attempt will be made immediately, and each subsequent attempt will be made after 'delay'.
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry
   * If attempts are exhausted the returned completion operator is simply the result of invoking attempt.
   * Note that the attempt function will be invoked on the given execution context for subsequent tries
   * and therefore must be thread safe (not touch unsafe mutable state).
   */
  def retry[T](attempt: Callable[CompletionStage[T]], attempts: Int, delay: java.time.Duration, scheduler: Scheduler, ec: ExecutionContext): CompletionStage[T] =
    scalaRetry(() ⇒ attempt.call().toScala, attempts, delay.asScala)(ec, scheduler).toJava
}

/**
 * Java 8+ API for Akka patterns such as `ask`, `pipe` and others which work with [[java.util.concurrent.CompletionStage]].
 *
 * For working with Scala [[scala.concurrent.Future]] from Java you may want to use [[akka.pattern.Patterns]] instead.
 */
@deprecated("Use Patterns instead.", since = "2.5.19")
object PatternsCS {
  import akka.actor.ActorRef
  import akka.japi
  import akka.pattern.{ ask ⇒ scalaAsk, gracefulStop ⇒ scalaGracefulStop, retry ⇒ scalaRetry }
  import akka.util.Timeout

  import scala.concurrent.duration._

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided.
   *
   * The CompletionStage will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   *   final CompletionStage<Object> f = PatternsCS.ask(worker, request, timeout);
   *   f.thenRun(result -> nextActor.tell(new EnrichedResult(request, result)));
   * }}}
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.15")
  def ask(actor: ActorRef, message: Any, timeout: Timeout): CompletionStage[AnyRef] =
    scalaAsk(actor, message)(timeout).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided.
   *
   * The CompletionStage will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   *   final CompletionStage<Object> f = PatternsCS.ask(worker, request, duration);
   *   f.thenRun(result -> nextActor.tell(new EnrichedResult(request, result)));
   * }}}
   */
  @deprecated("Use Patterns.ask instead.", since = "2.5.19")
  def ask(actor: ActorRef, message: Any, timeout: java.time.Duration): CompletionStage[AnyRef] =
    ask(actor, message, Timeout.create(timeout))

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = PatternsCS.askWithReplyTo(
   *   worker,
   *   askSender -> new Request(askSender),
   *   timeout);
   * }}}
   *
   * @param actor          the actor to be asked
   * @param messageFactory function taking an actor ref and returning the message to be sent
   * @param timeout        the timeout for the response before failing the returned completion operator
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.15")
  def askWithReplyTo(actor: ActorRef, messageFactory: japi.function.Function[ActorRef, Any], timeout: Timeout): CompletionStage[AnyRef] =
    extended.ask(actor, messageFactory.apply _)(timeout).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = PatternsCS.askWithReplyTo(
   *   worker,
   *   askSender -> new Request(askSender),
   *   timeout);
   * }}}
   *
   * @param actor          the actor to be asked
   * @param messageFactory function taking an actor ref and returning the message to be sent
   * @param timeout        the timeout for the response before failing the returned completion stage
   */
  @deprecated("Use Pattens.askWithReplyTo instead.", since = "2.5.19")
  def askWithReplyTo(actor: ActorRef, messageFactory: japi.function.Function[ActorRef, Any], timeout: java.time.Duration): CompletionStage[AnyRef] =
    extended.ask(actor, messageFactory.apply _)(Timeout.create(timeout)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided.
   *
   * The CompletionStage will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   *   final CompletionStage<Object> f = PatternsCS.ask(worker, request, timeout);
   *   f.thenRun(result -> nextActor.tell(new EnrichedResult(request, result)));
   * }}}
   */
  @deprecated("Use Pattens.ask which accepts java.time.Duration instead.", since = "2.5.19")
  def ask(actor: ActorRef, message: Any, timeoutMillis: Long): CompletionStage[AnyRef] =
    scalaAsk(actor, message)(new Timeout(timeoutMillis, TimeUnit.MILLISECONDS)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = PatternsCS.askWithReplyTo(
   *   worker,
   *   replyTo -> new Request(replyTo),
   *   timeout);
   * }}}
   *
   * @param actor          the actor to be asked
   * @param messageFactory function taking an actor ref to reply to and returning the message to be sent
   * @param timeoutMillis  the timeout for the response before failing the returned completion operator
   */
  @deprecated("Use Pattens.askWithReplyTo which accepts java.time.Duration instead.", since = "2.5.19")
  def askWithReplyTo(actor: ActorRef, messageFactory: japi.function.Function[ActorRef, Any], timeoutMillis: Long): CompletionStage[AnyRef] =
    askWithReplyTo(actor, messageFactory, Timeout(timeoutMillis.millis))

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided.
   *
   * The CompletionStage will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   *   final CompletionStage<Object> f = PatternsCS.ask(selection, request, timeout);
   *   f.thenRun(result -> nextActor.tell(new EnrichedResult(request, result)));
   * }}}
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.15")
  def ask(selection: ActorSelection, message: Any, timeout: Timeout): CompletionStage[AnyRef] =
    scalaAsk(selection, message)(timeout).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided.
   *
   * The CompletionStage will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   *   final CompletionStage<Object> f = PatternsCS.ask(selection, request, duration);
   *   f.thenRun(result -> nextActor.tell(new EnrichedResult(request, result)));
   * }}}
   */
  @deprecated("Use Patterns.ask instead.", since = "2.5.19")
  def ask(selection: ActorSelection, message: Any, timeout: java.time.Duration): CompletionStage[AnyRef] =
    ask(selection, message, Timeout.create(timeout))

  /**
   * <i>Java API for `akka.pattern.ask`:</i>
   * Sends a message asynchronously and returns a [[java.util.concurrent.CompletionStage]]
   * holding the eventual reply message; this means that the target [[akka.actor.ActorSelection]]
   * needs to send the result to the `sender` reference provided.
   *
   * The CompletionStage will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`). A typical reason for `AskTimeoutException` is that the
   * recipient actor didn't send a reply.
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
   *   final CompletionStage<Object> f = PatternsCS.ask(selection, request, timeout);
   *   f.thenRun(result -> nextActor.tell(new EnrichedResult(request, result)));
   * }}}
   */
  @deprecated("Use Pattens.ask which accepts java.time.Duration instead.", since = "2.5.19")
  def ask(selection: ActorSelection, message: Any, timeoutMillis: Long): CompletionStage[AnyRef] =
    scalaAsk(selection, message)(new Timeout(timeoutMillis, TimeUnit.MILLISECONDS)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * A variation of ask which allows to implement "replyTo" pattern by including
   * sender reference in message.
   *
   * {{{
   * final CompletionStage<Object> f = Patterns.askWithReplyTo(
   *   selection,
   *   askSender -> new Request(askSender),
   *   timeout);
   * }}}
   */
  @deprecated("Use Pattens.askWithReplyTo which accepts java.time.Duration instead.", since = "2.5.19")
  def askWithReplyTo(selection: ActorSelection, messageFactory: japi.Function[ActorRef, Any], timeoutMillis: Long): CompletionStage[AnyRef] =
    extended.ask(selection, messageFactory.apply _)(Timeout(timeoutMillis.millis)).toJava.asInstanceOf[CompletionStage[AnyRef]]

  /**
   * When this [[java.util.concurrent.CompletionStage]] finishes, send its result to the given
   * [[akka.actor.ActorRef]] or [[akka.actor.ActorSelection]].
   * Returns the original CompletionStage to allow method chaining.
   * If the future was completed with failure it is sent as a [[akka.actor.Status.Failure]]
   * to the recipient.
   *
   * <b>Recommended usage example:</b>
   *
   * {{{
   *   final CompletionStage<Object> f = PatternsCS.ask(worker, request, timeout);
   *   // apply some transformation (i.e. enrich with request info)
   *   final CompletionStage<Object> transformed = f.thenApply(result -> { ... });
   *   // send it on to the next operator
   *   PatternsCS.pipe(transformed, context).to(nextActor);
   * }}}
   */
  @deprecated("Use Patterns.pipe instead.", since = "2.5.19")
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
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def gracefulStop(target: ActorRef, timeout: FiniteDuration): CompletionStage[java.lang.Boolean] =
    scalaGracefulStop(target, timeout).toJava.asInstanceOf[CompletionStage[java.lang.Boolean]]

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
  @deprecated("Use Patterns.gracefulStop instead.", since = "2.5.19")
  def gracefulStop(target: ActorRef, timeout: java.time.Duration): CompletionStage[java.lang.Boolean] =
    scalaGracefulStop(target, timeout.asScala).toJava.asInstanceOf[CompletionStage[java.lang.Boolean]]

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
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def gracefulStop(target: ActorRef, timeout: FiniteDuration, stopMessage: Any): CompletionStage[java.lang.Boolean] =
    scalaGracefulStop(target, timeout, stopMessage).toJava.asInstanceOf[CompletionStage[java.lang.Boolean]]

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
  @deprecated("Use Patterns.gracefulStop instead.", since = "2.5.19")
  def gracefulStop(target: ActorRef, timeout: java.time.Duration, stopMessage: Any): CompletionStage[java.lang.Boolean] =
    scalaGracefulStop(target, timeout.asScala, stopMessage).toJava.asInstanceOf[CompletionStage[java.lang.Boolean]]

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def after[T](duration: FiniteDuration, scheduler: Scheduler, context: ExecutionContext, value: Callable[CompletionStage[T]]): CompletionStage[T] =
    afterCompletionStage(duration, scheduler)(value.call())(context)

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with the success or failure of the provided Callable
   * after the specified duration.
   */
  @deprecated("Use Patterns.after instead.", since = "2.5.19")
  def after[T](duration: java.time.Duration, scheduler: Scheduler, context: ExecutionContext, value: Callable[CompletionStage[T]]): CompletionStage[T] =
    afterCompletionStage(duration.asScala, scheduler)(value.call())(context)

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def after[T](duration: FiniteDuration, scheduler: Scheduler, context: ExecutionContext, value: CompletionStage[T]): CompletionStage[T] =
    afterCompletionStage(duration, scheduler)(value)(context)

  /**
   * Returns a [[java.util.concurrent.CompletionStage]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  @deprecated("Use Patterns.after instead.", since = "2.5.19")
  def after[T](duration: java.time.Duration, scheduler: Scheduler, context: ExecutionContext, value: CompletionStage[T]): CompletionStage[T] =
    afterCompletionStage(duration.asScala, scheduler)(value)(context)

  /**
   * Returns an internally retrying [[java.util.concurrent.CompletionStage]]
   * The first attempt will be made immediately, and each subsequent attempt will be made after 'delay'.
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry
   * If attempts are exhausted the returned completion operator is simply the result of invoking attempt.
   * Note that the attempt function will be invoked on the given execution context for subsequent tries
   * and therefore must be thread safe (not touch unsafe mutable state).
   */
  @deprecated("Use Patterns.retry instead.", since = "2.5.19")
  def retry[T](attempt: Callable[CompletionStage[T]], attempts: Int, delay: java.time.Duration, scheduler: Scheduler, ec: ExecutionContext): CompletionStage[T] =
    scalaRetry(() ⇒ attempt.call().toScala, attempts, delay.asScala)(ec, scheduler).toJava
}
