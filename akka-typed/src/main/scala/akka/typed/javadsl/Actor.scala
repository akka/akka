/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.javadsl

import java.util.function.{ Function ⇒ JFunction }

import scala.reflect.ClassTag

import akka.util.OptionVal
import akka.japi.function.{ Function2 ⇒ JapiFunction2 }
import akka.japi.function.Procedure2
import akka.japi.pf.PFBuilder

import akka.typed.Behavior
import akka.typed.ExtensibleBehavior
import akka.typed.Signal
import akka.typed.ActorRef
import akka.typed.SupervisorStrategy
import akka.typed.scaladsl.{ ActorContext ⇒ SAC }

import akka.typed.internal.BehaviorImpl
import akka.typed.internal.Restarter
import akka.typed.internal.TimerSchedulerImpl

object Actor {

  private val _unitFunction = (_: SAC[Any], _: Any) ⇒ ()
  private def unitFunction[T] = _unitFunction.asInstanceOf[((SAC[T], Signal) ⇒ Unit)]

  /**
   * `deferred` is a factory for a behavior. Creation of the behavior instance is deferred until
   * the actor is started, as opposed to `Actor.immutable` that creates the behavior instance
   * immediately before the actor is running. The `factory` function pass the `ActorContext`
   * as parameter and that can for example be used for spawning child actors.
   *
   * `deferred` is typically used as the outer most behavior when spawning an actor, but it
   * can also be returned as the next behavior when processing a message or signal. In that
   * case it will be "undeferred" immediately after it is returned, i.e. next message will be
   * processed by the undeferred behavior.
   */
  def deferred[T](factory: akka.japi.function.Function[ActorContext[T], Behavior[T]]): Behavior[T] =
    Behavior.DeferredBehavior(ctx ⇒ factory.apply(ctx.asJava))

  /**
   * Factory for creating a [[MutableBehavior]] that typically holds mutable state as
   * instance variables in the concrete [[MutableBehavior]] implementation class.
   *
   * Creation of the behavior instance is deferred, i.e. it is created via the `factory`
   * function. The reason for the deferred creation is to avoid sharing the same instance in
   * multiple actors, and to create a new instance when the actor is restarted.
   *
   * @param producer
   *          behavior factory that takes the child actor’s context as argument
   * @return the deferred behavior
   */
  def mutable[T](factory: akka.japi.function.Function[ActorContext[T], MutableBehavior[T]]): Behavior[T] =
    deferred(factory)

  /**
   * Mutable behavior can be implemented by extending this class and implement the
   * abstract method [[MutableBehavior#onMessage]] and optionally override
   * [[MutableBehavior#onSignal]].
   *
   * Instances of this behavior should be created via [[Actor#mutable]] and if
   * the [[ActorContext]] is needed it can be passed as a constructor parameter
   * from the factory function.
   *
   * @see [[Actor#mutable]]
   */
  abstract class MutableBehavior[T] extends ExtensibleBehavior[T] {
    private var _receive: OptionVal[Receive[T]] = OptionVal.None
    private def receive: Receive[T] = _receive match {
      case OptionVal.None ⇒
        val receive = createReceive
        _receive = OptionVal.Some(receive)
        receive
      case OptionVal.Some(r) ⇒ r
    }

    @throws(classOf[Exception])
    override final def receiveMessage(ctx: akka.typed.ActorContext[T], msg: T): Behavior[T] =
      receive.receiveMessage(msg)

    @throws(classOf[Exception])
    override final def receiveSignal(ctx: akka.typed.ActorContext[T], msg: Signal): Behavior[T] =
      receive.receiveSignal(msg)

    def createReceive: Receive[T]

    def receiveBuilder: ReceiveBuilder[T] = ReceiveBuilder.create
  }

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
   * guardian of an [[akka.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * This constructor is called immutable because the behavior instance doesn't
   * have or close over any mutable state. Processing the next message
   * results in a new behavior that can potentially be different from this one.
   * State is updated by returning a new behavior that holds the new immutable
   * state.
   */
  def immutable[T](onMessage: JapiFunction2[ActorContext[T], T, Behavior[T]]): Behavior[T] =
    new BehaviorImpl.ImmutableBehavior((ctx, msg) ⇒ onMessage.apply(ctx.asJava, msg))

  /**
   * Construct an actor behavior that can react to both incoming messages and
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an [[akka.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * This constructor is called immutable because the behavior instance doesn't
   * have or close over any mutable state. Processing the next message
   * results in a new behavior that can potentially be different from this one.
   * State is updated by returning a new behavior that holds the new immutable
   * state.
   */
  def immutable[T](
    onMessage: JapiFunction2[ActorContext[T], T, Behavior[T]],
    onSignal:  JapiFunction2[ActorContext[T], Signal, Behavior[T]]): Behavior[T] = {
    new BehaviorImpl.ImmutableBehavior(
      (ctx, msg) ⇒ onMessage.apply(ctx.asJava, msg),
      { case (ctx, sig) ⇒ onSignal.apply(ctx.asJava, sig) })
  }

  /**
   * Constructs an actor behavior builder that can build a behavior that can react to both
   * incoming messages and lifecycle signals.
   *
   * This constructor is called immutable because the behavior instance doesn't
   * have or close over any mutable state. Processing the next message
   * results in a new behavior that can potentially be different from this one.
   * State is updated by returning a new behavior that holds the new immutable
   * state. If no change is desired, use {@link #same}.
   *
   * @param type the supertype of all messages accepted by this behavior
   * @return the behavior builder
   */
  def immutable[T](`type`: Class[T]): BehaviorBuilder[T] = BehaviorBuilder.create[T]

  /**
   * This type of Behavior wraps another Behavior while allowing you to perform
   * some action upon each received message or signal. It is most commonly used
   * for logging or tracing what a certain Actor does.
   */
  def tap[T](
    onMessage: Procedure2[ActorContext[T], T],
    onSignal:  Procedure2[ActorContext[T], Signal],
    behavior:  Behavior[T]): Behavior[T] = {
    BehaviorImpl.tap(
      (ctx, msg) ⇒ onMessage.apply(ctx.asJava, msg),
      (ctx, sig) ⇒ onSignal.apply(ctx.asJava, sig),
      behavior)
  }

  /**
   * Behavior decorator that copies all received message to the designated
   * monitor [[akka.typed.ActorRef]] before invoking the wrapped behavior. The
   * wrapped behavior can evolve (i.e. return different behavior) without needing to be
   * wrapped in a `monitor` call again.
   */
  def monitor[T](monitor: ActorRef[T], behavior: Behavior[T]): Behavior[T] = {
    BehaviorImpl.tap(
      (ctx, msg) ⇒ monitor ! msg,
      unitFunction,
      behavior)
  }

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
   *    Actor.supervise(dbConnector)
   *      .onFailure(SupervisorStrategy.restart) // handle all NonFatal exceptions
   *
   * final Behavior[DbCommand] dbSpecificResumes =
   *    Actor.supervise(dbConnector)
   *      .onFailure[IndexOutOfBoundsException](SupervisorStrategy.resume) // resume for IndexOutOfBoundsException exceptions
   * }}}
   */
  def supervise[T](wrapped: Behavior[T]): Supervise[T] =
    new Supervise[T](wrapped)

  final class Supervise[T] private[akka] (wrapped: Behavior[T]) {
    /**
     * Specify the [[SupervisorStrategy]] to be invoked when the wrapped behaior throws.
     *
     * Only exceptions of the given type (and their subclasses) will be handled by this supervision behavior.
     */
    def onFailure[Thr <: Throwable](clazz: Class[Thr], strategy: SupervisorStrategy): Behavior[T] =
      akka.typed.internal.Restarter(Behavior.validateAsInitial(wrapped), strategy)(ClassTag(clazz))

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
   * Behavior&lt;String> s = immutable((ctx, msg) -> {
   *     System.out.println(msg);
   *     return same();
   *   });
   * Behavior&lt;Number> n = widened(s, pf -> pf.
   *         match(BigInteger.class, i -> "BigInteger(" + i + ")").
   *         match(BigDecimal.class, d -> "BigDecimal(" + d + ")")
   *         // drop all other kinds of Number
   *     );
   * }}}
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

  trait Receive[T] {
    def receiveMessage(msg: T): Behavior[T]
    def receiveSignal(msg: Signal): Behavior[T]
  }
}
