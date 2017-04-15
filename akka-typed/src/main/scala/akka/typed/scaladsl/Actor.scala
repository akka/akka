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
  def watch[U](other: ActorRef[U]): ActorRef[U]

  /**
   * Revoke the registration established by `watch`. A [[Terminated]]
   * notification will not subsequently be received for the referenced Actor.
   */
  def unwatch[U](other: ActorRef[U]): ActorRef[U]

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

  // FIXME check that all behaviors can cope with not getting PreStart as first message

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
   * Stateless[String]((ctx, msg) => println(msg)).widen[Number] {
   *   case b: BigDecimal => s"BigDecimal(&dollar;b)"
   *   case i: BigInteger => s"BigInteger(&dollar;i)"
   *   // drop all other kinds of Number
   * }
   * }}}
   */
  final case class Widened[T, U](behavior: Behavior[T], matcher: PartialFunction[U, T]) extends Behavior[U] {
    private def postProcess(behv: Behavior[T]): Behavior[U] =
      if (isUnhandled(behv)) Unhandled
      else if (isAlive(behv)) {
        val next = canonicalize(behv, behavior)
        if (next eq behavior) Same else Widened(next, matcher)
      } else Stopped

    override def management(ctx: AC[U], msg: Signal): Behavior[U] =
      postProcess(behavior.management(ctx.as[T], msg))

    override def message(ctx: AC[U], msg: U): Behavior[U] =
      matcher.applyOrElse(msg, nullFun) match {
        case null        ⇒ Unhandled
        case transformed ⇒ postProcess(behavior.message(ctx.as[T], transformed))
      }

    override def toString: String = s"${behavior.toString}.widen(${LineNumbers(matcher)})"
  }

  /**
   * Wrap a behavior factory so that it runs upon PreStart, i.e. behavior creation
   * is deferred to the child actor instead of running within the parent.
   */
  final case class Deferred[T](factory: ActorContext[T] ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: AC[T], msg: Signal): Behavior[T] = {
      if (msg != PreStart) throw new IllegalStateException(s"Deferred must receive PreStart as first message (got $msg)")
      Behavior.preStart(factory(ctx), ctx)
    }

    override def message(ctx: AC[T], msg: T): Behavior[T] =
      throw new IllegalStateException(s"Deferred must receive PreStart as first message (got $msg)")

    override def toString: String = s"Deferred(${LineNumbers(factory)})"
  }

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior. This is provided in order to
   * avoid the allocation overhead of recreating the current behavior where
   * that is not necessary.
   */
  def Same[T]: Behavior[T] = sameBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior, including the hint that the
   * message has not been handled. This hint may be used by composite
   * behaviors that delegate (partial) handling to other behaviors.
   */
  def Unhandled[T]: Behavior[T] = unhandledBehavior.asInstanceOf[Behavior[T]]

  /*
   * TODO write a Behavior that waits for all child actors to stop and then
   * runs some cleanup before stopping. The factory for this behavior should
   * stop and watch all children to get the process started.
   */

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure. The PostStop
   * signal that results from stopping this actor will NOT be passed to the
   * current behavior, it will be effectively ignored.
   */
  def Stopped[T]: Behavior[T] = stoppedBehavior.asInstanceOf[Behavior[T]]

  /**
   * A behavior that treats every incoming message as unhandled.
   */
  def Empty[T]: Behavior[T] = emptyBehavior.asInstanceOf[Behavior[T]]

  /**
   * A behavior that ignores every incoming message and returns “same”.
   */
  def Ignore[T]: Behavior[T] = ignoreBehavior.asInstanceOf[Behavior[T]]

  /**
   * Construct an actor behavior that can react to both incoming messages and
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an [[akka.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * This constructor is called stateful because processing the next message
   * results in a new behavior that can potentially be different from this one.
   */
  final case class Stateful[T](
    behavior: (ActorContext[T], T) ⇒ Behavior[T],
    signal:   (ActorContext[T], Signal) ⇒ Behavior[T] = Behavior.unhandledSignal.asInstanceOf[(ActorContext[T], Signal) ⇒ Behavior[T]])
    extends Behavior[T] {
    override def management(ctx: AC[T], msg: Signal): Behavior[T] = signal(ctx, msg)
    override def message(ctx: AC[T], msg: T) = behavior(ctx, msg)
    override def toString = s"Stateful(${LineNumbers(behavior)})"
  }

  /**
   * Construct an actor behavior that can react to incoming messages but not to
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an [[akka.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * This constructor is called stateless because it cannot be replaced by
   * another one after it has been installed. It is most useful for leaf actors
   * that do not create child actors themselves.
   */
  final case class Stateless[T](behavior: (ActorContext[T], T) ⇒ Any) extends Behavior[T] {
    override def management(ctx: AC[T], msg: Signal): Behavior[T] = Unhandled
    override def message(ctx: AC[T], msg: T): Behavior[T] = {
      behavior(ctx, msg)
      this
    }
    override def toString = s"Static(${LineNumbers(behavior)})"
  }

  /**
   * This type of Behavior wraps another Behavior while allowing you to perform
   * some action upon each received message or signal. It is most commonly used
   * for logging or tracing what a certain Actor does.
   */
  final case class Tap[T](
    signal:   (ActorContext[T], Signal) ⇒ _,
    mesg:     (ActorContext[T], T) ⇒ _,
    behavior: Behavior[T]) extends Behavior[T] {
    private def canonical(behv: Behavior[T]): Behavior[T] =
      if (isUnhandled(behv)) Unhandled
      else if (behv eq sameBehavior) Same
      else if (isAlive(behv)) Tap(signal, mesg, behv)
      else Stopped
    override def management(ctx: AC[T], msg: Signal): Behavior[T] = {
      signal(ctx, msg)
      canonical(behavior.management(ctx, msg))
    }
    override def message(ctx: AC[T], msg: T): Behavior[T] = {
      mesg(ctx, msg)
      canonical(behavior.message(ctx, msg))
    }
    override def toString = s"Tap(${LineNumbers(signal)},${LineNumbers(mesg)},$behavior)"
  }

  /**
   * Behavior decorator that copies all received message to the designated
   * monitor [[akka.typed.ActorRef]] before invoking the wrapped behavior. The
   * wrapped behavior can evolve (i.e. be stateful) without needing to be
   * wrapped in a `monitor` call again.
   */
  object Monitor {
    def apply[T](monitor: ActorRef[T], behavior: Behavior[T]): Tap[T] = Tap(unitFunction, (_, msg) ⇒ monitor ! msg, behavior)
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
