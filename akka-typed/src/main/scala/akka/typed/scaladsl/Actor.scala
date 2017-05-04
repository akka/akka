/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package scaladsl

import scala.reflect.ClassTag

import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.typed.internal.BehaviorImpl

@ApiMayChange
object Actor {
  import Behavior._

  private val _unitFunction = (_: ActorContext[Any], _: Any) ⇒ ()
  private def unitFunction[T] = _unitFunction.asInstanceOf[((ActorContext[T], Signal) ⇒ Unit)]

  final implicit class BehaviorDecorators[T](val behavior: Behavior[T]) extends AnyVal {
    /**
     * Widen the wrapped Behavior by placing a funnel in front of it: the supplied
     * PartialFunction decides which message to pull in (those that it is defined
     * at) and may transform the incoming message to place them into the wrapped
     * Behavior’s type hierarchy. Signals are not transformed.
     *
     * Example:
     * {{{
     * immutable[String] { (ctx, msg) => println(msg); same }.widen[Number] {
     *   case b: BigDecimal => s"BigDecimal(&dollar;b)"
     *   case i: BigInteger => s"BigInteger(&dollar;i)"
     *   // drop all other kinds of Number
     * }
     * }}}
     */
    def widen[U](matcher: PartialFunction[U, T]): Behavior[U] =
      BehaviorImpl.Widened(behavior, matcher)
  }

  /**
   * Wrap a behavior factory so that it runs upon PreStart, i.e. behavior creation
   * is deferred to the child actor instead of running within the parent.
   */
  def deferred[T](factory: ActorContext[T] ⇒ Behavior[T]): Behavior[T] =
    Behavior.DeferredBehavior(factory)

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
  def mutable[T](factory: ActorContext[T] ⇒ MutableBehavior[T]): Behavior[T] =
    deferred(factory)

  /**
   * Mutable behavior can be implemented by extending this class and implement the
   * abstract method [[MutableBehavior#onMessage]] and optionally override
   * [[MutableBehavior#onSignal]].
   *
   * Instances of this behavior should be created via [[Actor#Mutable]] and if
   * the [[ActorContext]] is needed it can be passed as a constructor parameter
   * from the factory function.
   *
   * @see [[Actor#Mutable]]
   */
  abstract class MutableBehavior[T] extends ExtensibleBehavior[T] {
    @throws(classOf[Exception])
    override final def receiveMessage(ctx: akka.typed.ActorContext[T], msg: T): Behavior[T] =
      onMessage(msg)

    /**
     * Implement this method to process an incoming message and return the next behavior.
     *
     * The returned behavior can in addition to normal behaviors be one of the canned special objects:
     * <ul>
     * <li>returning `stopped` will terminate this Behavior</li>
     * <li>returning `this` or `same` designates to reuse the current Behavior</li>
     * <li>returning `unhandled` keeps the same Behavior and signals that the message was not yet handled</li>
     * </ul>
     *
     */
    @throws(classOf[Exception])
    def onMessage(msg: T): Behavior[T]

    @throws(classOf[Exception])
    override final def receiveSignal(ctx: akka.typed.ActorContext[T], msg: Signal): Behavior[T] =
      onSignal(msg)

    /**
     * Override this method to process an incoming [[akka.typed.Signal]] and return the next behavior.
     * This means that all lifecycle hooks, ReceiveTimeout, Terminated and Failed messages
     * can initiate a behavior change.
     *
     * The returned behavior can in addition to normal behaviors be one of the canned special objects:
     *
     *  * returning `stopped` will terminate this Behavior
     *  * returning `this` or `same` designates to reuse the current Behavior
     *  * returning `unhandled` keeps the same Behavior and signals that the message was not yet handled
     *
     * By default, partial function is empty and does not handle any signals.
     */
    @throws(classOf[Exception])
    def onSignal: PartialFunction[Signal, Behavior[T]] = PartialFunction.empty
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
   * these will be stopped as part of the shutdown procedure. The PostStop
   * signal that results from stopping this actor will NOT be passed to the
   * current behavior, it will be effectively ignored.
   */
  def stopped[T]: Behavior[T] = Behavior.stopped

  /**
   * A behavior that treats every incoming message as unhandled.
   */
  def empty[T]: Behavior[T] = Behavior.empty

  /**
   * A behavior that ignores every incoming message and returns “same”.
   */
  def ignore[T]: Behavior[T] = Behavior.ignore

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
  def immutable[T](onMessage: (ActorContext[T], T) ⇒ Behavior[T]): Immutable[T] =
    new Immutable(onMessage)

  final class Immutable[T](onMessage: (ActorContext[T], T) ⇒ Behavior[T])
    extends BehaviorImpl.ImmutableBehavior[T](onMessage) {

    def onSignal(onSignal: PartialFunction[(ActorContext[T], Signal), Behavior[T]]): Behavior[T] =
      new BehaviorImpl.ImmutableBehavior(onMessage, onSignal)
  }

  /**
   * This type of Behavior wraps another Behavior while allowing you to perform
   * some action upon each received message or signal. It is most commonly used
   * for logging or tracing what a certain Actor does.
   */
  def tap[T](
    onMessage: Function2[ActorContext[T], T, _],
    onSignal:  Function2[ActorContext[T], Signal, _],
    behavior:  Behavior[T]): Behavior[T] =
    BehaviorImpl.Tap(onMessage, onSignal, behavior)

  /**
   * Behavior decorator that copies all received message to the designated
   * monitor [[akka.typed.ActorRef]] before invoking the wrapped behavior. The
   * wrapped behavior can evolve (i.e. return different behavior) without needing to be
   * wrapped in a `monitor` call again.
   */
  def monitor[T](monitor: ActorRef[T], behavior: Behavior[T]): Behavior[T] =
    tap((_, msg) ⇒ monitor ! msg, unitFunction, behavior)

  /**
   * Wrap the given behavior such that it is restarted (i.e. reset to its
   * initial state) whenever it throws an exception of the given class or a
   * subclass thereof. Exceptions that are not subtypes of `Thr` will not be
   * caught and thus lead to the termination of the actor.
   *
   * It is possible to specify different supervisor strategies, such as restart,
   * resume, backoff.
   *
   * Example:
   * {{{
   * val dbConnector: Behavior[DbCommand] = ...
   * val dbRestarts = Restarter[DbException]().wrap(dbConnector)
   * }}}
   */
  def restarter[Thr <: Throwable: ClassTag](strategy: SupervisorStrategy = SupervisorStrategy.restart): Restarter[Thr] =
    new Restarter(implicitly, strategy)

  final class Restarter[Thr <: Throwable: ClassTag](c: ClassTag[Thr], strategy: SupervisorStrategy) {
    def wrap[T](b: Behavior[T]): Behavior[T] = akka.typed.internal.Restarter(Behavior.validateAsInitial(b), strategy)(c)
  }

  // TODO
  // final case class Selective[T](timeout: FiniteDuration, selector: PartialFunction[T, Behavior[T]], onTimeout: () ⇒ Behavior[T])

}
