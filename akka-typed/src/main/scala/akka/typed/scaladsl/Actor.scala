/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package scaladsl

import akka.util.LineNumbers
import scala.reflect.ClassTag
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContextExecutor
import scala.deprecatedInheritance
import akka.typed.{ ActorContext ⇒ AC }
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit

/**
 * An Actor is given by the combination of a [[Behavior]] and a context in
 * which this behavior is executed. As per the Actor Model an Actor can perform
 * the following actions when processing a message:
 *
 *  - send a finite number of messages to other Actors it knows
 *  - create a finite number of Actors
 *  - designate the behavior for the next message
 *
 * In Akka the first capability is accessed by using the `!` or `tell` method
 * on an [[ActorRef]], the second is provided by [[ActorContext#spawn]]
 * and the third is implicit in the signature of [[Behavior]] in that the next
 * behavior is always returned from the message processing logic.
 *
 * An `ActorContext` in addition provides access to the Actor’s own identity (“`self`”),
 * the [[ActorSystem]] it is part of, methods for querying the list of child Actors it
 * created, access to [[Terminated DeathWatch]] and timed message scheduling.
 */
@DoNotInherit
@ApiMayChange
trait ActorContext[T] { this: akka.typed.javadsl.ActorContext[T] ⇒

  /**
   * The identity of this Actor, bound to the lifecycle of this Actor instance.
   * An Actor with the same name that lives before or after this instance will
   * have a different [[ActorRef]].
   */
  def self: ActorRef[T]

  /**
   * Return the mailbox capacity that was configured by the parent for this actor.
   */
  def mailboxCapacity: Int

  /**
   * The [[ActorSystem]] to which this Actor belongs.
   */
  def system: ActorSystem[Nothing]

  /**
   * The list of child Actors created by this Actor during its lifetime that
   * are still alive, in no particular order.
   */
  def children: Iterable[ActorRef[Nothing]]

  /**
   * The named child Actor if it is alive.
   */
  def child(name: String): Option[ActorRef[Nothing]]

  /**
   * Create a child Actor from the given [[akka.typed.Behavior]] under a randomly chosen name.
   * It is good practice to name Actors wherever practical.
   */
  def spawnAnonymous[U](behavior: Behavior[U], deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[U]

  /**
   * Create a child Actor from the given [[akka.typed.Behavior]] and with the given name.
   */
  def spawn[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[U]

  /**
   * Force the child Actor under the given name to terminate after it finishes
   * processing its current message. Nothing happens if the ActorRef does not
   * refer to a current child actor.
   *
   * @return whether the passed-in [[ActorRef]] points to a current child Actor
   */
  def stop(child: ActorRef[_]): Boolean

  /**
   * Register for [[Terminated]] notification once the Actor identified by the
   * given [[ActorRef]] terminates. This notification is also generated when the
   * [[ActorSystem]] to which the referenced Actor belongs is declared as
   * failed (e.g. in reaction to being unreachable).
   */
  def watch[U](other: ActorRef[U]): Unit

  /**
   * Revoke the registration established by `watch`. A [[Terminated]]
   * notification will not subsequently be received for the referenced Actor.
   */
  def unwatch[U](other: ActorRef[U]): Unit

  /**
   * Schedule the sending of a notification in case no other
   * message is received during the given period of time. The timeout starts anew
   * with each received message. Provide `Duration.Undefined` to switch off this
   * mechanism.
   */
  def setReceiveTimeout(d: FiniteDuration, msg: T): Unit

  /**
   * Cancel the sending of receive timeout notifications.
   */
  def cancelReceiveTimeout(): Unit

  /**
   * Schedule the sending of the given message to the given target Actor after
   * the given time period has elapsed. The scheduled action can be cancelled
   * by invoking [[akka.actor.Cancellable#cancel]] on the returned
   * handle.
   */
  def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): akka.actor.Cancellable

  /**
   * This Actor’s execution context. It can be used to run asynchronous tasks
   * like [[scala.concurrent.Future]] combinators.
   */
  implicit def executionContext: ExecutionContextExecutor

  /**
   * Create a child actor that will wrap messages such that other Actor’s
   * protocols can be ingested by this Actor. You are strongly advised to cache
   * these ActorRefs or to stop them when no longer needed.
   *
   * The name of the child actor will be composed of a unique identifier
   * starting with a dollar sign to which the given `name` argument is
   * appended, with an inserted hyphen between these two parts. Therefore
   * the given `name` argument does not need to be unique within the scope
   * of the parent actor.
   */
  def spawnAdapter[U](f: U ⇒ T, name: String): ActorRef[U]

  /**
   * Create an anonymous child actor that will wrap messages such that other Actor’s
   * protocols can be ingested by this Actor. You are strongly advised to cache
   * these ActorRefs or to stop them when no longer needed.
   */
  def spawnAdapter[U](f: U ⇒ T): ActorRef[U] = spawnAdapter(f, "")

}

@ApiMayChange
object Actor {
  import Behavior._

  final implicit class BehaviorDecorators[T](val behavior: Behavior[T]) extends AnyVal {
    /**
     * Widen the wrapped Behavior by placing a funnel in front of it: the supplied
     * PartialFunction decides which message to pull in (those that it is defined
     * at) and may transform the incoming message to place them into the wrapped
     * Behavior’s type hierarchy. Signals are not transformed.
     *
     * see also [[Actor.Widened]]
     */
    def widen[U](matcher: PartialFunction[U, T]): Behavior[U] = Widened(behavior, matcher)
  }

  private val _nullFun = (_: Any) ⇒ null
  private def nullFun[T] = _nullFun.asInstanceOf[Any ⇒ T]
  private implicit class ContextAs[T](val ctx: AC[T]) extends AnyVal {
    def as[U] = ctx.asInstanceOf[AC[U]]
  }

  /**
   * Widen the wrapped Behavior by placing a funnel in front of it: the supplied
   * PartialFunction decides which message to pull in (those that it is defined
   * at) and may transform the incoming message to place them into the wrapped
   * Behavior’s type hierarchy. Signals are not transformed.
   *
   * Example:
   * {{{
   * Immutable[String] { (ctx, msg) => println(msg); Same }.widen[Number] {
   *   case b: BigDecimal => s"BigDecimal(&dollar;b)"
   *   case i: BigInteger => s"BigInteger(&dollar;i)"
   *   // drop all other kinds of Number
   * }
   * }}}
   */
  final case class Widened[T, U](behavior: Behavior[T], matcher: PartialFunction[U, T]) extends ExtensibleBehavior[U] {
    private def postProcess(behv: Behavior[T], ctx: AC[T]): Behavior[U] =
      if (isUnhandled(behv)) Unhandled
      else if (isAlive(behv)) {
        val next = canonicalize(behv, behavior, ctx)
        if (next eq behavior) Same else Widened(next, matcher)
      } else Stopped

    override def receiveSignal(ctx: AC[U], signal: Signal): Behavior[U] =
      postProcess(Behavior.interpretSignal(behavior, ctx.as[T], signal), ctx.as[T])

    override def receiveMessage(ctx: AC[U], msg: U): Behavior[U] =
      matcher.applyOrElse(msg, nullFun) match {
        case null        ⇒ Unhandled
        case transformed ⇒ postProcess(Behavior.interpretMessage(behavior, ctx.as[T], transformed), ctx.as[T])
      }

    override def toString: String = s"${behavior.toString}.widen(${LineNumbers(matcher)})"
  }

  /**
   * Wrap a behavior factory so that it runs upon PreStart, i.e. behavior creation
   * is deferred to the child actor instead of running within the parent.
   */
  final case class Deferred[T](factory: ActorContext[T] ⇒ Behavior[T]) extends DeferredBehavior[T] {
    /** "undefer" the deferred behavior */
    override def apply(ctx: AC[T]): Behavior[T] = factory(ctx)

    override def toString: String = s"Deferred(${LineNumbers(factory)})"
  }

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
  def Mutable[T](factory: ActorContext[T] ⇒ MutableBehavior[T]): Behavior[T] =
    Deferred(factory)

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
     *  * returning `Stopped` will terminate this Behavior
     *  * returning `this` or `Same` designates to reuse the current Behavior
     *  * returning `Unhandled` keeps the same Behavior and signals that the message was not yet handled
     *
     * By default, this method returns `Unhandled`.
     */
    @throws(classOf[Exception])
    def onSignal(msg: Signal): Behavior[T] =
      Unhandled
  }

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior. This is provided in order to
   * avoid the allocation overhead of recreating the current behavior where
   * that is not necessary.
   */
  def Same[T]: Behavior[T] = Behavior.same

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior, including the hint that the
   * message has not been handled. This hint may be used by composite
   * behaviors that delegate (partial) handling to other behaviors.
   */
  def Unhandled[T]: Behavior[T] = Behavior.unhandled

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure. The PostStop
   * signal that results from stopping this actor will NOT be passed to the
   * current behavior, it will be effectively ignored.
   */
  def Stopped[T]: Behavior[T] = Behavior.stopped

  /**
   * A behavior that treats every incoming message as unhandled.
   */
  def Empty[T]: Behavior[T] = Behavior.empty

  /**
   * A behavior that ignores every incoming message and returns “same”.
   */
  def Ignore[T]: Behavior[T] = Behavior.ignore

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
  final case class Immutable[T](
    onMessage: (ActorContext[T], T) ⇒ Behavior[T],
    onSignal:  (ActorContext[T], Signal) ⇒ Behavior[T] = Behavior.unhandledSignal.asInstanceOf[(ActorContext[T], Signal) ⇒ Behavior[T]])
    extends ExtensibleBehavior[T] {
    override def receiveSignal(ctx: AC[T], msg: Signal): Behavior[T] = onSignal(ctx, msg)
    override def receiveMessage(ctx: AC[T], msg: T) = onMessage(ctx, msg)
    override def toString = s"Immutable(${LineNumbers(onMessage)})"
  }

  /**
   * This type of Behavior wraps another Behavior while allowing you to perform
   * some action upon each received message or signal. It is most commonly used
   * for logging or tracing what a certain Actor does.
   */
  final case class Tap[T](
    onMessage: Function2[ActorContext[T], T, _],
    onSignal:  Function2[ActorContext[T], Signal, _],
    behavior:  Behavior[T]) extends ExtensibleBehavior[T] {

    private def canonical(behv: Behavior[T]): Behavior[T] =
      if (isUnhandled(behv)) Unhandled
      else if ((behv eq SameBehavior) || (behv eq this)) Same
      else if (isAlive(behv)) Tap(onMessage, onSignal, behv)
      else Stopped
    override def receiveSignal(ctx: AC[T], signal: Signal): Behavior[T] = {
      onSignal(ctx, signal)
      canonical(Behavior.interpretSignal(behavior, ctx, signal))
    }
    override def receiveMessage(ctx: AC[T], msg: T): Behavior[T] = {
      onMessage(ctx, msg)
      canonical(Behavior.interpretMessage(behavior, ctx, msg))
    }
    override def toString = s"Tap(${LineNumbers(onSignal)},${LineNumbers(onMessage)},$behavior)"
  }

  /**
   * Behavior decorator that copies all received message to the designated
   * monitor [[akka.typed.ActorRef]] before invoking the wrapped behavior. The
   * wrapped behavior can evolve (i.e. return different behavior) without needing to be
   * wrapped in a `monitor` call again.
   */
  object Monitor {
    def apply[T](monitor: ActorRef[T], behavior: Behavior[T]): Tap[T] = Tap((_, msg) ⇒ monitor ! msg, unitFunction, behavior)
  }

  /**
   * Wrap the given behavior such that it is restarted (i.e. reset to its
   * initial state) whenever it throws an exception of the given class or a
   * subclass thereof. Exceptions that are not subtypes of `Thr` will not be
   * caught and thus lead to the termination of the actor.
   *
   * It is possible to specify that the actor shall not be restarted but
   * resumed. This entails keeping the same state as before the exception was
   * thrown and is thus less safe. If you use `OnFailure.RESUME` you should at
   * least not hold mutable data fields or collections within the actor as those
   * might be in an inconsistent state (the exception might have interrupted
   * normal processing); avoiding mutable state is possible by returning a fresh
   * behavior with the new state after every message.
   *
   * Example:
   * {{{
   * val dbConnector: Behavior[DbCommand] = ...
   * val dbRestarts = Restarter[DbException].wrap(dbConnector)
   * }}}
   */
  object Restarter {
    class Apply[Thr <: Throwable](c: ClassTag[Thr], resume: Boolean) {
      def wrap[T](b: Behavior[T]): Behavior[T] = patterns.Restarter(Behavior.validateAsInitial(b), resume)()(c)
      def mutableWrap[T](b: Behavior[T]): Behavior[T] = patterns.MutableRestarter(Behavior.validateAsInitial(b), resume)(c)
    }

    def apply[Thr <: Throwable: ClassTag](resume: Boolean = false): Apply[Thr] = new Apply(implicitly, resume)
  }

  // TODO
  // final case class Selective[T](timeout: FiniteDuration, selector: PartialFunction[T, Behavior[T]], onTimeout: () ⇒ Behavior[T])

  /**
   * INTERNAL API.
   */
  private[akka] val _unhandledFunction = (_: Any) ⇒ Unhandled[Nothing]
  /**
   * INTERNAL API.
   */
  private[akka] def unhandledFunction[T, U] = _unhandledFunction.asInstanceOf[(T ⇒ Behavior[U])]

  /**
   * INTERNAL API.
   */
  private[akka] val _unitFunction = (_: ActorContext[Any], _: Any) ⇒ ()
  /**
   * INTERNAL API.
   */
  private[akka] def unitFunction[T] = _unitFunction.asInstanceOf[((ActorContext[T], Signal) ⇒ Unit)]

}
