/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.annotation.{ ApiMayChange, DoNotInherit, InternalApi }
import akka.actor.typed.internal._

import scala.reflect.ClassTag

/**
 * Factories for [[akka.actor.typed.Behavior]].
 */
@ApiMayChange
object Behaviors {

  private val _unitFunction = (_: ActorContext[Any], _: Any) ⇒ ()
  private def unitFunction[T] = _unitFunction.asInstanceOf[((ActorContext[T], Signal) ⇒ Unit)]

  /**
   * `setup` is a factory for a behavior. Creation of the behavior instance is deferred until
   * the actor is started, as opposed to [[Behaviors.receive]] that creates the behavior instance
   * immediately before the actor is running. The `factory` function pass the `ActorContext`
   * as parameter and that can for example be used for spawning child actors.
   *
   * `setup` is typically used as the outer most behavior when spawning an actor, but it
   * can also be returned as the next behavior when processing a message or signal. In that
   * case it will be started immediately after it is returned, i.e. next message will be
   * processed by the started behavior.
   */
  def setup[T](factory: ActorContext[T] ⇒ Behavior[T]): Behavior[T] =
    Behavior.DeferredBehavior(factory)

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
   * Construct an actor behavior that can react to both incoming messages and
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an [[akka.actor.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * This constructor is called immutable because the behavior instance does not
   * need and in fact should not use (close over) mutable variables, but instead
   * return a potentially different behavior encapsulating any state changes.
   */
  def receive[T](onMessage: (ActorContext[T], T) ⇒ Behavior[T]): Receive[T] =
    new ReceiveImpl(onMessage)

  /**
   * Simplified version of [[Receive]] with only a single argument - the message
   * to be handled. Useful for when the context is already accessible by other means,
   * like being wrapped in an [[setup]] or similar.
   *
   * Construct an actor behavior that can react to both incoming messages and
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an [[akka.actor.typed.ActorSystem]]) it will be executed within an
   * [[ActorContext]] that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * This constructor is called immutable because the behavior instance does not
   * need and in fact should not use (close over) mutable variables, but instead
   * return a potentially different behavior encapsulating any state changes.
   */
  def receiveMessage[T](onMessage: T ⇒ Behavior[T]): Receive[T] =
    new ReceiveMessageImpl(onMessage)

  /**
   * Construct an immutable actor behavior from a partial message handler which treats undefined messages as unhandled.
   */
  def receivePartial[T](onMessage: PartialFunction[(ActorContext[T], T), Behavior[T]]): Receive[T] =
    Behaviors.receive[T] { (ctx, t) ⇒
      onMessage.applyOrElse((ctx, t), (_: (ActorContext[T], T)) ⇒ Behaviors.unhandled[T])
    }

  /**
   * Construct an immutable actor behavior from a partial message handler which treats undefined messages as unhandled.
   */
  def receiveMessagePartial[T](onMessage: PartialFunction[T, Behavior[T]]): Receive[T] =
    Behaviors.receive[T] { (_, t) ⇒
      onMessage.applyOrElse(t, (_: T) ⇒ Behaviors.unhandled[T])
    }

  /**
   * Construct an actor behavior that can react to lifecycle signals only.
   */
  def receiveSignal[T](handler: PartialFunction[(ActorContext[T], Signal), Behavior[T]]): Behavior[T] =
    receive[T]((_, _) ⇒ same).receiveSignal(handler)

  /**
   * This type of Behavior wraps another Behavior while allowing you to perform
   * some action upon each received message or signal. It is most commonly used
   * for logging or tracing what a certain Actor does.
   */
  def tap[T](
    onMessage: (ActorContext[T], T) ⇒ _,
    onSignal:  (ActorContext[T], Signal) ⇒ _, // FIXME use partial function here also?
    behavior:  Behavior[T]): Behavior[T] =
    BehaviorImpl.tap(onMessage, onSignal, behavior)

  /**
   * Behavior decorator that copies all received message to the designated
   * monitor [[akka.actor.typed.ActorRef]] before invoking the wrapped behavior. The
   * wrapped behavior can evolve (i.e. return different behavior) without needing to be
   * wrapped in a `monitor` call again.
   */
  def monitor[T](monitor: ActorRef[T], behavior: Behavior[T]): Behavior[T] =
    tap((_, msg) ⇒ monitor ! msg, unitFunction, behavior)

  /**
   * Wrap the given behavior with the given [[SupervisorStrategy]] for
   * the given exception.
   * Exceptions that are not subtypes of `Thr` will not be
   * caught and thus lead to the termination of the actor.
   *
   * It is possible to specify different supervisor strategies, such as restart,
   * resume, backoff.
   *
   * Note that only [[scala.util.control.NonFatal]] throwables will trigger the supervision strategy.
   *
   * Example:
   * {{{
   * val dbConnector: Behavior[DbCommand] = ...
   *
   * val dbRestarts =
   *    Behaviors.supervise(dbConnector)
   *      .onFailure(SupervisorStrategy.restart) // handle all NonFatal exceptions
   *
   * val dbSpecificResumes =
   *    Behaviors.supervise(dbConnector)
   *      .onFailure[IndexOutOfBoundsException](SupervisorStrategy.resume) // resume for IndexOutOfBoundsException exceptions
   * }}}
   */
  def supervise[T](wrapped: Behavior[T]): Supervise[T] =
    new Supervise[T](wrapped)

  private final val NothingClassTag = ClassTag(classOf[Nothing])
  private final val ThrowableClassTag = ClassTag(classOf[Throwable])
  final class Supervise[T] private[akka] (val wrapped: Behavior[T]) extends AnyVal {
    /** Specify the [[SupervisorStrategy]] to be invoked when the wrapped behavior throws. */
    def onFailure[Thr <: Throwable: ClassTag](strategy: SupervisorStrategy): Behavior[T] = {
      val tag = implicitly[ClassTag[Thr]]
      val effectiveTag = if (tag == NothingClassTag) ThrowableClassTag else tag
      Supervisor(Behavior.validateAsInitial(wrapped), strategy)(effectiveTag)
    }
  }

  /**
   * Support for scheduled `self` messages in an actor.
   * It takes care of the lifecycle of the timers such as cancelling them when the actor
   * is restarted or stopped.
   * @see [[TimerScheduler]]
   */
  def withTimers[T](factory: TimerScheduler[T] ⇒ Behavior[T]): Behavior[T] =
    TimerSchedulerImpl.withTimers(factory)

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
  def withMdc[T](mdcForMessage: T ⇒ Map[String, Any])(behavior: Behavior[T]): Behavior[T] =
    withMdc[T](Map.empty[String, Any], mdcForMessage)(behavior)

  /**
   * Static MDC (Mapped Diagnostic Context)
   *
   * @param staticMdc This MDC is setup in the logging context for every message
   * @param behavior The actual behavior handling the messages, the MDC is used for the log entries logged through
   *                 `ActorContext.log`
   *
   * See also [[akka.actor.typed.Logger.withMdc]]
   */
  def withMdc[T](staticMdc: Map[String, Any])(behavior: Behavior[T]): Behavior[T] =
    withMdc[T](staticMdc, (_: T) ⇒ Map.empty[String, Any])(behavior)

  /**
   * Combination of static and per message MDC (Mapped Diagnostic Context).
   *
   * Each message will get the static MDC plus the MDC returned for the message. If the same key
   * are in both the static and the per message MDC the per message one overwrites the static one
   * in the resulting log entries.
   *
   * The `staticMdc` or `mdcForMessage` may be empty.
   *
   * @param staticMdc A static MDC applied for each message
   * @param mdcForMessage Is invoked before each message is handled, allowing to setup MDC, MDC is cleared after
   *                 each message processing by the inner behavior is done.
   * @param behavior The actual behavior handling the messages, the MDC is used for the log entries logged through
   *                 `ActorContext.log`
   *
   * See also [[akka.actor.typed.Logger.withMdc]]
   */
  def withMdc[T](staticMdc: Map[String, Any], mdcForMessage: T ⇒ Map[String, Any])(behavior: Behavior[T]): Behavior[T] =
    WithMdcBehavior[T](staticMdc, mdcForMessage, behavior)

  // TODO
  // final case class Selective[T](timeout: FiniteDuration, selector: PartialFunction[T, Behavior[T]], onTimeout: () ⇒ Behavior[T])

  /**
   * Immutable behavior that exposes additional fluent DSL methods
   * to further change the message or signal reception behavior.
   */
  @DoNotInherit
  trait Receive[T] extends ExtensibleBehavior[T] {
    def receiveSignal(onSignal: PartialFunction[(ActorContext[T], Signal), Behavior[T]]): Behavior[T]

    // TODO orElse can be defined here
  }

  @InternalApi
  private[akka] final class ReceiveImpl[T](onMessage: (ActorContext[T], T) ⇒ Behavior[T])
    extends BehaviorImpl.ReceiveBehavior[T](onMessage) with Receive[T] {

    override def receiveSignal(onSignal: PartialFunction[(ActorContext[T], Signal), Behavior[T]]): Behavior[T] =
      new BehaviorImpl.ReceiveBehavior(onMessage, onSignal)
  }
  @InternalApi
  private[akka] final class ReceiveMessageImpl[T](onMessage: T ⇒ Behavior[T])
    extends BehaviorImpl.ReceiveMessageBehavior[T](onMessage) with Receive[T] {

    override def receiveSignal(onSignal: PartialFunction[(ActorContext[T], Signal), Behavior[T]]): Behavior[T] =
      new BehaviorImpl.ReceiveMessageBehavior[T](onMessage, onSignal)
  }

}
