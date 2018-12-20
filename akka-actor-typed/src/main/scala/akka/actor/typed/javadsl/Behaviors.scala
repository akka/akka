/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import java.util.Collections
import java.util.function.{ Function ⇒ JFunction }

import akka.actor.typed.{ ActorRef, Behavior, BehaviorInterceptor, Signal, SupervisorStrategy }
import akka.actor.typed.internal.{ BehaviorImpl, Supervisor, TimerSchedulerImpl, WithMdcBehaviorInterceptor }
import akka.actor.typed.scaladsl
import akka.annotation.ApiMayChange
import akka.japi.function.{ Function2 ⇒ JapiFunction2 }
import akka.japi.pf.PFBuilder
import akka.util.unused

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * Factories for [[akka.actor.typed.Behavior]].
 */
@ApiMayChange
object Behaviors {

  private[this] val _two2same = new JapiFunction2[ActorContext[Any], Any, Behavior[Any]] {
    override def apply(context: ActorContext[Any], msg: Any): Behavior[Any] = same
  }
  private[this] def two2same[T] = _two2same.asInstanceOf[JapiFunction2[ActorContext[T], T, Behavior[T]]]

  /**
   * `setup` is a factory for a behavior. Creation of the behavior instance is deferred until
   * the actor is started, as opposed to [[Behaviors#receive]] that creates the behavior instance
   * immediately before the actor is running. The `factory` function pass the `ActorContext`
   * as parameter and that can for example be used for spawning child actors.
   *
   * `setup` is typically used as the outer most behavior when spawning an actor, but it
   * can also be returned as the next behavior when processing a message or signal. In that
   * case it will be started immediately after it is returned, i.e. next message will be
   * processed by the started behavior.
   */
  def setup[T](factory: akka.japi.function.Function[ActorContext[T], Behavior[T]]): Behavior[T] =
    Behavior.DeferredBehavior(ctx ⇒ factory.apply(ctx.asJava))

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior. This is provided in order to
   * avoid the allocation overhead of recreating the current behavior where
   * that is not necessary.
   */
  def same[T]: Behavior[T] = Behavior.same

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior, including the hint that the
   * message has not been handled. This hint may be used by composite
   * behaviors that delegate (partial) handling to other behaviors.
   */
  def unhandled[T]: Behavior[T] = Behavior.unhandled

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure.
   *
   * The PostStop signal that results from stopping this actor will be passed to the
   * current behavior. All other messages and signals will effectively be
   * ignored.
   */
  def stopped[T]: Behavior[T] = Behavior.stopped

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure.
   *
   * The PostStop signal that results from stopping this actor will be passed to the
   * given `postStop` behavior. All other messages and signals will effectively be
   * ignored.
   */
  def stopped[T](postStop: Behavior[T]): Behavior[T] = Behavior.stopped(postStop)

  /**
   * A behavior that treats every incoming message as unhandled.
   */
  def empty[T]: Behavior[T] = Behavior.empty

  /**
   * A behavior that ignores every incoming message and returns “same”.
   */
  def ignore[T]: Behavior[T] = Behavior.ignore

  /**
   * Construct an actor behavior that can react to incoming messages but not to
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an [[akka.actor.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * Compared to using [[AbstractBehavior]] this factory is a more functional style
   * of defining the `Behavior`. Processing the next message results in a new behavior
   * that can potentially be different from this one. State is maintained by returning
   * a new behavior that holds the new immutable state.
   */
  def receive[T](onMessage: JapiFunction2[ActorContext[T], T, Behavior[T]]): Behavior[T] =
    new BehaviorImpl.ReceiveBehavior((ctx, msg) ⇒ onMessage.apply(ctx.asJava, msg))

  /**
   * Simplified version of [[receive]] with only a single argument - the message
   * to be handled. Useful for when the context is already accessible by other means,
   * like being wrapped in an [[setup]] or similar.
   *
   * Construct an actor behavior that can react to incoming messages but not to
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an [[akka.actor.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * Compared to using [[AbstractBehavior]] this factory is a more functional style
   * of defining the `Behavior`. Processing the next message results in a new behavior
   * that can potentially be different from this one. State is maintained by returning
   * a new behavior that holds the new immutable state.
   */
  def receiveMessage[T](onMessage: akka.japi.Function[T, Behavior[T]]): Behavior[T] =
    new BehaviorImpl.ReceiveBehavior((_, msg) ⇒ onMessage.apply(msg))

  /**
   * Construct an actor behavior that can react to both incoming messages and
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an [[akka.actor.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * Compared to using [[AbstractBehavior]] this factory is a more functional style
   * of defining the `Behavior`. Processing the next message results in a new behavior
   * that can potentially be different from this one. State is maintained by returning
   * a new behavior that holds the new immutable state.
   */
  def receive[T](
    onMessage: JapiFunction2[ActorContext[T], T, Behavior[T]],
    onSignal:  JapiFunction2[ActorContext[T], Signal, Behavior[T]]): Behavior[T] = {
    new BehaviorImpl.ReceiveBehavior(
      (ctx, msg) ⇒ onMessage.apply(ctx.asJava, msg),
      { case (ctx, sig) ⇒ onSignal.apply(ctx.asJava, sig) })
  }

  /**
   * Constructs an actor behavior builder that can build a behavior that can react to both
   * incoming messages and lifecycle signals.
   *
   * Compared to using [[AbstractBehavior]] this factory is a more functional style
   * of defining the `Behavior`. Processing the next message results in a new behavior
   * that can potentially be different from this one. State is maintained by returning
   * a new behavior that holds the new immutable state.
   *
   * @param type the supertype of all messages accepted by this behavior
   * @return the behavior builder
   */
  def receive[T](@unused `type`: Class[T]): BehaviorBuilder[T] = BehaviorBuilder.create[T]

  /**
   * Construct an actor behavior that can react to lifecycle signals only.
   */
  def receiveSignal[T](handler: JapiFunction2[ActorContext[T], Signal, Behavior[T]]): Behavior[T] = {
    receive(two2same, handler)
  }

  /**
   * Intercept messages and signals for a `behavior` by first passing them to a [[akka.actor.typed.BehaviorInterceptor]]
   *
   * When a behavior returns a new behavior as a result of processing a signal or message and that behavior already contains
   * the same interceptor (defined by the [[akka.actor.typed.BehaviorInterceptor#isSame]] method) only the innermost interceptor
   * is kept. This is to protect against stack overflow when recursively defining behaviors.
   */
  def intercept[O, I](behaviorInterceptor: BehaviorInterceptor[O, I], behavior: Behavior[I]): Behavior[O] =
    BehaviorImpl.intercept(behaviorInterceptor)(behavior)

  /**
   * Behavior decorator that copies all received message to the designated
   * monitor [[akka.actor.typed.ActorRef]] before invoking the wrapped behavior. The
   * wrapped behavior can evolve (i.e. return different behavior) without needing to be
   * wrapped in a `monitor` call again.
   */
  def monitor[T](monitor: ActorRef[T], behavior: Behavior[T]): Behavior[T] =
    scaladsl.Behaviors.monitor(monitor, behavior)

  /**
   * Wrap the given behavior such that it is restarted (i.e. reset to its
   * initial state) whenever it throws an exception of the given class or a
   * subclass thereof. Exceptions that are not subtypes of `Thr` will not be
   * caught and thus lead to the termination of the actor.
   *
   * It is possible to specify different supervisor strategies, such as restart,
   * resume, backoff.
   *
   * The [[SupervisorStrategy]] is only invoked for "non fatal" (see [[scala.util.control.NonFatal]])
   * exceptions.
   *
   * Example:
   * {{{
   * final Behavior[DbCommand] dbConnector = ...
   *
   * final Behavior[DbCommand] dbRestarts =
   *    Behaviors.supervise(dbConnector)
   *      .onFailure(SupervisorStrategy.restart) // handle all NonFatal exceptions
   *
   * final Behavior[DbCommand] dbSpecificResumes =
   *    Behaviors.supervise(dbConnector)
   *      .onFailure[IndexOutOfBoundsException](SupervisorStrategy.resume) // resume for IndexOutOfBoundsException exceptions
   * }}}
   */
  def supervise[T](wrapped: Behavior[T]): Supervise[T] =
    new Supervise[T](wrapped)

  final class Supervise[T] private[akka] (wrapped: Behavior[T]) {
    /**
     * Specify the [[SupervisorStrategy]] to be invoked when the wrapped behavior throws.
     *
     * Only exceptions of the given type (and their subclasses) will be handled by this supervision behavior.
     */
    def onFailure[Thr <: Throwable](clazz: Class[Thr], strategy: SupervisorStrategy): Behavior[T] =
      Supervisor(Behavior.validateAsInitial(wrapped), strategy)(ClassTag(clazz))

    /**
     * Specify the [[SupervisorStrategy]] to be invoked when the wrapped behaior throws.
     *
     * All non-fatal (see [[scala.util.control.NonFatal]]) exceptions types will be handled using the given strategy.
     */
    def onFailure(strategy: SupervisorStrategy): Behavior[T] =
      onFailure(classOf[Exception], strategy)
  }

  /**
   * Widen the wrapped Behavior by placing a funnel in front of it: the supplied
   * PartialFunction decides which message to pull in (those that it is defined
   * at) and may transform the incoming message to place them into the wrapped
   * Behavior’s type hierarchy. Signals are not transformed.
   *
   * Example:
   * {{{
   * Behavior<String> s = Behaviors.receive((ctx, msg) -> {
   *     System.out.println(msg);
   *     return Behaviors.same();
   *   });
   * Behavior<Number> n = Behaviors.widened(s, pf -> pf.
   *         match(BigInteger.class, i -> "BigInteger(" + i + ")").
   *         match(BigDecimal.class, d -> "BigDecimal(" + d + ")")
   *         // drop all other kinds of Number
   *     );
   * }}}
   *
   * Scheduled messages via [[TimerScheduler]] can currently not be used
   * together with `widen`, see issue #25318.
   *
   * @param behavior
   *          the behavior that will receive the selected messages
   * @param selector
   *          a partial function builder for describing the selection and
   *          transformation
   * @return a behavior of the widened type
   */
  def widened[T, U](behavior: Behavior[T], selector: JFunction[PFBuilder[U, T], PFBuilder[U, T]]): Behavior[U] =
    BehaviorImpl.widened(behavior, selector.apply(new PFBuilder).build())

  /**
   * Support for scheduled `self` messages in an actor.
   * It takes care of the lifecycle of the timers such as cancelling them when the actor
   * is restarted or stopped.
   * @see [[TimerScheduler]]
   */
  def withTimers[T](factory: akka.japi.function.Function[TimerScheduler[T], Behavior[T]]): Behavior[T] =
    TimerSchedulerImpl.withTimers(timers ⇒ factory.apply(timers))

  /**
   * Per message MDC (Mapped Diagnostic Context) logging.
   *
   * @param mdcForMessage Is invoked before each message is handled, allowing to setup MDC, MDC is cleared after
   *                 each message processing by the inner behavior is done.
   * @param behavior The actual behavior handling the messages, the MDC is used for the log entries logged through
   *                 `ActorContext.log`
   *
   * See also [[akka.actor.typed.Logger.withMdc]]
   */
  def withMdc[T](
    mdcForMessage: akka.japi.function.Function[T, java.util.Map[String, Any]], behavior: Behavior[T]): Behavior[T] =
    withMdc(Collections.emptyMap[String, Any], mdcForMessage, behavior)

  /**
   * Static MDC (Mapped Diagnostic Context)
   *
   * @param staticMdc This MDC is setup in the logging context for every message
   * @param behavior The actual behavior handling the messages, the MDC is used for the log entries logged through
   *                 `ActorContext.log`
   *
   * See also [[akka.actor.typed.Logger.withMdc]]
   */
  def withMdc[T](staticMdc: java.util.Map[String, Any], behavior: Behavior[T]): Behavior[T] =
    withMdc(staticMdc, null, behavior)

  /**
   * Combination of static and per message MDC (Mapped Diagnostic Context).
   *
   * Each message will get the static MDC plus the MDC returned for the message. If the same key
   * are in both the static and the per message MDC the per message one overwrites the static one
   * in the resulting log entries.
   *
   * * The `staticMdc` or `mdcForMessage` may be empty.
   *
   * @param staticMdc A static MDC applied for each message
   * @param mdcForMessage Is invoked before each message is handled, allowing to setup MDC, MDC is cleared after
   *                 each message processing by the inner behavior is done.
   * @param behavior The actual behavior handling the messages, the MDC is used for the log entries logged through
   *                 `ActorContext.log`
   *
   * See also [[akka.actor.typed.Logger.withMdc]]
   */
  def withMdc[T](
    staticMdc:     java.util.Map[String, Any],
    mdcForMessage: akka.japi.function.Function[T, java.util.Map[String, Any]],
    behavior:      Behavior[T]): Behavior[T] = {

    def asScalaMap(m: java.util.Map[String, Any]): Map[String, Any] = {
      if (m == null || m.isEmpty) Map.empty[String, Any]
      else m.asScala.toMap
    }

    val mdcForMessageFun: T ⇒ Map[String, Any] =
      if (mdcForMessage == null) Map.empty
      else {
        message ⇒ asScalaMap(mdcForMessage.apply(message))
      }

    WithMdcBehaviorInterceptor[T](
      asScalaMap(staticMdc),
      mdcForMessageFun,
      behavior)
  }

}
