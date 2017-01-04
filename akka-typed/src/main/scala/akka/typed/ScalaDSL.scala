/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.annotation.tailrec
import Behavior._
import akka.util.LineNumbers

/**
 * This object holds several behavior factories and combinators that can be
 * used to construct Behavior instances.
 */
object ScalaDSL {

  // FIXME check that all behaviors can cope with not getting PreStart as first message

  implicit class BehaviorDecorators[T](val behavior: Behavior[T]) extends AnyVal {
    /**
     * Widen the type of this Behavior by providing a filter function that permits
     * only a subtype of the widened set of messages.
     */
    def widen[U >: T](matcher: PartialFunction[U, T]): Behavior[U] = Widened(behavior, matcher)
    /**
     * Combine the two behaviors such that incoming messages are distributed
     * to both of them, each one evolving its state independently.
     */
    def &&(other: Behavior[T]): Behavior[T] = And(behavior, other)
    /**
     * Combine the two behaviors such that incoming messages are given first to
     * the left behavior and are then only passed on to the right behavior if
     * the left one returned Unhandled.
     */
    def ||(other: Behavior[T]): Behavior[T] = Or(behavior, other)
  }

  /**
   * Widen the wrapped Behavior by placing a funnel in front of it: the supplied
   * PartialFunction decides which message to pull in (those that it is defined
   * at) and may transform the incoming message to place them into the wrapped
   * Behavior’s type hierarchy. Signals are not transformed.
   */
  final case class Widened[T, U >: T](behavior: Behavior[T], matcher: PartialFunction[U, T]) extends Behavior[U] {
    private def postProcess(ctx: ActorContext[U], behv: Behavior[T]): Behavior[U] =
      if (isUnhandled(behv)) Unhandled
      else if (isAlive(behv)) {
        val next = canonicalize(behv, behavior)
        if (next eq behavior) Same else Widened(next, matcher)
      } else Stopped

    override def management(ctx: ActorContext[U], msg: Signal): Behavior[U] =
      postProcess(ctx, behavior.management(ctx.asInstanceOf[ActorContext[T]], msg))

    override def message(ctx: ActorContext[U], msg: U): Behavior[U] =
      if (matcher.isDefinedAt(msg))
        postProcess(ctx, behavior.message(ctx.asInstanceOf[ActorContext[T]], matcher(msg)))
      else Unhandled

    override def toString: String = s"${behavior.toString}.widen(${LineNumbers(matcher)})"
  }

  /**
   * Wrap a behavior factory so that it runs upon PreStart, i.e. behavior creation
   * is deferred to the child actor instead of running within the parent.
   */
  final case class Deferred[T](factory: () ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      if (msg != PreStart) throw new IllegalStateException(s"Deferred must receive PreStart as first message (got $msg)")
      Behavior.preStart(factory(), ctx)
    }

    override def message(ctx: ActorContext[T], msg: T): Behavior[T] =
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
   * This behavior does not handle any inputs, it is completely inert.
   */
  def Empty[T]: Behavior[T] = emptyBehavior.asInstanceOf[Behavior[T]]

  /**
   * This behavior does not handle any inputs, it is completely inert.
   */
  def Ignore[T]: Behavior[T] = ignoreBehavior.asInstanceOf[Behavior[T]]

  /**
   * Algebraic Data Type modeling either a [[Msg message]] or a
   * [[Sig signal]], including the [[ActorContext]]. This type is
   * used by several of the behaviors defined in this DSL, see for example
   * [[Full]].
   */
  sealed trait MessageOrSignal[T]
  /**
   * A message bundled together with the current [[ActorContext]].
   */
  @SerialVersionUID(1L)
  final case class Msg[T](ctx: ActorContext[T], msg: T) extends MessageOrSignal[T]
  /**
   * A signal bundled together with the current [[ActorContext]].
   */
  @SerialVersionUID(1L)
  final case class Sig[T](ctx: ActorContext[T], signal: Signal) extends MessageOrSignal[T]

  /**
   * This type of behavior allows to handle all incoming messages within
   * the same user-provided partial function, be that a user message or a system
   * signal. For messages that do not match the partial function the same
   * behavior is emitted without change. This does entail that unhandled
   * failures of child actors will lead to a failure in this actor.
   *
   * For the lifecycle notifications pertaining to the actor itself this
   * behavior includes a fallback mechanism: an unhandled [[PreRestart]] signal
   * will terminate all child actors (transitively) and then emit a [[PostStop]]
   * signal in addition, whereas an unhandled [[PostRestart]] signal will emit
   * an additional [[PreStart]] signal.
   */
  final case class Full[T](behavior: PartialFunction[MessageOrSignal[T], Behavior[T]]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      lazy val fallback: (MessageOrSignal[T]) ⇒ Behavior[T] = {
        case Sig(context, PreRestart) ⇒
          context.children foreach { child ⇒
            context.unwatch[Nothing](child)
            context.stop(child)
          }
          behavior.applyOrElse(Sig(context, PostStop), fallback)
        case _ ⇒ Unhandled
      }
      behavior.applyOrElse(Sig(ctx, msg), fallback)
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      behavior.applyOrElse(Msg(ctx, msg), unhandledFunction)
    }
    override def toString = s"Full(${LineNumbers(behavior)})"
  }

  /**
   * This type of behavior expects a total function that describes the actor’s
   * reaction to all system signals or user messages, without providing a
   * fallback mechanism for either. If you use partial function literal syntax
   * to create the supplied function then any message not matching the list of
   * cases will fail this actor with a [[scala.MatchError]].
   */
  final case class FullTotal[T](behavior: MessageOrSignal[T] ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal) = behavior(Sig(ctx, msg))
    override def message(ctx: ActorContext[T], msg: T) = behavior(Msg(ctx, msg))
    override def toString = s"FullTotal(${LineNumbers(behavior)})"
  }

  /**
   * This type of behavior is created from a total function from the declared
   * message type to the next behavior, which means that all possible incoming
   * messages for the given type must be handled. All system signals are
   * ignored by this behavior, which implies that a failure of a child actor
   * will be escalated unconditionally.
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  final case class Total[T](behavior: T ⇒ Behavior[T]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = msg match {
      case _ ⇒ Unhandled
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = behavior(msg)
    override def toString = s"Total(${LineNumbers(behavior)})"
  }

  /**
   * This type of Behavior is created from a partial function from the declared
   * message type to the next behavior, flagging all unmatched messages as
   * [[#Unhandled]]. All system signals are
   * ignored by this behavior, which implies that a failure of a child actor
   * will be escalated unconditionally.
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  final case class Partial[T](behavior: PartialFunction[T, Behavior[T]]) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = msg match {
      case _ ⇒ Unhandled
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = behavior.applyOrElse(msg, unhandledFunction)
    override def toString = s"Partial(${LineNumbers(behavior)})"
  }

  /**
   * This type of Behavior wraps another Behavior while allowing you to perform
   * some action upon each received message or signal. It is most commonly used
   * for logging or tracing what a certain Actor does.
   */
  final case class Tap[T](f: PartialFunction[MessageOrSignal[T], Unit], behavior: Behavior[T]) extends Behavior[T] {
    private def canonical(behv: Behavior[T]): Behavior[T] =
      if (isUnhandled(behv)) Unhandled
      else if (behv eq sameBehavior) Same
      else if (isAlive(behv)) Tap(f, behv)
      else Stopped
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      f.applyOrElse(Sig(ctx, msg), unitFunction)
      canonical(behavior.management(ctx, msg))
    }
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      f.applyOrElse(Msg(ctx, msg), unitFunction)
      canonical(behavior.message(ctx, msg))
    }
    override def toString = s"Tap(${LineNumbers(f)},$behavior)"
  }
  object Tap {
    def monitor[T](monitor: ActorRef[T], behavior: Behavior[T]): Tap[T] = Tap({ case Msg(_, msg) ⇒ monitor ! msg }, behavior)
  }

  /**
   * This type of behavior is a variant of [[Total]] that does not
   * allow the actor to change behavior. It is an efficient choice for stateless
   * actors, possibly entering such a behavior after finishing its
   * initialization (which may be modeled using any of the other behavior types).
   *
   * This behavior type is most useful for leaf actors that do not create child
   * actors themselves.
   */
  final case class Static[T](behavior: T ⇒ Unit) extends Behavior[T] {
    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = Unhandled
    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      behavior(msg)
      this
    }
    override def toString = s"Static(${LineNumbers(behavior)})"
  }

  /**
   * This behavior allows sending messages to itself without going through the
   * Actor’s mailbox. A message sent like this will be processed before the next
   * message is taken out of the mailbox. In case of Actor failures outstanding
   * messages that were sent to the synchronous self reference will be lost.
   *
   * This decorator is useful for passing messages between the left and right
   * sides of [[And]] and [[Or]] combinators.
   */
  final case class SynchronousSelf[T](f: ActorRef[T] ⇒ Behavior[T]) extends Behavior[T] {

    private class B extends Behavior[T] {
      private val inbox = Inbox[T]("synchronousSelf")
      private var _behavior = Behavior.validateAsInitial(f(inbox.ref))
      private def behavior = _behavior
      private def setBehavior(ctx: ActorContext[T], b: Behavior[T]): Unit =
        _behavior = canonicalize(b, _behavior)

      // FIXME should we protect against infinite loops?
      @tailrec private def run(ctx: ActorContext[T], next: Behavior[T]): Behavior[T] = {
        setBehavior(ctx, next)
        if (inbox.hasMessages) run(ctx, behavior.message(ctx, inbox.receiveMsg()))
        else if (isUnhandled(next)) Unhandled
        else if (isAlive(next)) this
        else Stopped
      }

      override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] =
        run(ctx, behavior.management(ctx, msg))
      override def message(ctx: ActorContext[T], msg: T): Behavior[T] =
        run(ctx, behavior.message(ctx, msg))

      override def toString: String = s"SynchronousSelf($behavior)"
    }

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      if (msg != PreStart) throw new IllegalStateException(s"SynchronousSelf must receive PreStart as first message (got $msg)")
      Behavior.preStart(new B(), ctx)
    }

    override def message(ctx: ActorContext[T], msg: T): Behavior[T] =
      throw new IllegalStateException(s"SynchronousSelf must receive PreStart as first message (got $msg)")

    override def toString: String = s"SynchronousSelf(${LineNumbers(f)})"
  }

  /**
   * A behavior combinator that feeds incoming messages and signals both into
   * the left and right sub-behavior and allows them to evolve independently of
   * each other. When one of the sub-behaviors terminates the other takes over
   * exclusively. When both sub-behaviors respond to a [[Failed]] signal, the
   * response with the higher precedence is chosen (see [[Failed$]]).
   */
  final case class And[T](left: Behavior[T], right: Behavior[T]) extends Behavior[T] {

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      val l = left.management(ctx, msg)
      val r = right.management(ctx, msg)
      if (isUnhandled(l) && isUnhandled(r)) Unhandled
      else {
        val nextLeft = canonicalize(l, left)
        val nextRight = canonicalize(r, right)
        val leftAlive = isAlive(nextLeft)
        val rightAlive = isAlive(nextRight)

        if (leftAlive && rightAlive) And(nextLeft, nextRight)
        else if (leftAlive) nextLeft
        else if (rightAlive) nextRight
        else Stopped
      }
    }

    override def message(ctx: ActorContext[T], msg: T): Behavior[T] = {
      val l = left.message(ctx, msg)
      val r = right.message(ctx, msg)
      if (isUnhandled(l) && isUnhandled(r)) Unhandled
      else {
        val nextLeft = canonicalize(l, left)
        val nextRight = canonicalize(r, right)
        val leftAlive = isAlive(nextLeft)
        val rightAlive = isAlive(nextRight)

        if (leftAlive && rightAlive) And(nextLeft, nextRight)
        else if (leftAlive) nextLeft
        else if (rightAlive) nextRight
        else Stopped
      }
    }
  }

  /**
   * A behavior combinator that feeds incoming messages and signals either into
   * the left or right sub-behavior and allows them to evolve independently of
   * each other. The message or signal is passed first into the left sub-behavior
   * and only if that results in [[#Unhandled]] is it passed to the right
   * sub-behavior. When one of the sub-behaviors terminates the other takes over
   * exclusively. When both sub-behaviors respond to a [[Failed]] signal, the
   * response with the higher precedence is chosen (see [[Failed$]]).
   */
  final case class Or[T](left: Behavior[T], right: Behavior[T]) extends Behavior[T] {

    override def management(ctx: ActorContext[T], msg: Signal): Behavior[T] =
      left.management(ctx, msg) match {
        case b if isUnhandled(b) ⇒
          val r = right.management(ctx, msg)
          if (isUnhandled(r)) Unhandled
          else {
            val nr = canonicalize(r, right)
            if (isAlive(nr)) Or(left, nr) else left
          }
        case nl ⇒
          val next = canonicalize(nl, left)
          if (isAlive(next)) Or(next, right) else right
      }

    override def message(ctx: ActorContext[T], msg: T): Behavior[T] =
      left.message(ctx, msg) match {
        case b if isUnhandled(b) ⇒
          val r = right.message(ctx, msg)
          if (isUnhandled(r)) Unhandled
          else {
            val nr = canonicalize(r, right)
            if (isAlive(nr)) Or(left, nr) else left
          }
        case nl ⇒
          val next = canonicalize(nl, left)
          if (isAlive(next)) Or(next, right) else right
      }
  }

  // TODO
  // final case class Selective[T](timeout: FiniteDuration, selector: PartialFunction[T, Behavior[T]], onTimeout: () ⇒ Behavior[T])

  /**
   * A behavior decorator that extracts the self [[ActorRef]] while receiving the
   * the first signal or message and uses that to construct the real behavior
   * (which will then also receive that signal or message).
   *
   * Example:
   * {{{
   * SelfAware[MyCommand] { self =>
   *   Simple {
   *     case cmd =>
   *   }
   * }
   * }}}
   *
   * This can also be used together with implicitly sender-capturing message
   * types:
   * {{{
   * final case class OtherMsg(msg: String)(implicit val replyTo: ActorRef[Reply])
   *
   * SelfAware[MyCommand] { implicit self =>
   *   Simple {
   *     case cmd =>
   *       other ! OtherMsg("hello") // assuming Reply <: MyCommand
   *   }
   * }
   * }}}
   */
  def SelfAware[T](behavior: ActorRef[T] ⇒ Behavior[T]): Behavior[T] =
    FullTotal {
      case Sig(ctx, PreStart) ⇒ Behavior.preStart(behavior(ctx.self), ctx)
      case msg                ⇒ throw new IllegalStateException(s"SelfAware must receive PreStart as first message (got $msg)")
    }

  /**
   * A behavior decorator that extracts the [[ActorContext]] while receiving the
   * the first signal or message and uses that to construct the real behavior
   * (which will then also receive that signal or message).
   *
   * Example:
   * {{{
   * ContextAware[MyCommand] { ctx => Simple {
   *     case cmd =>
   *       ...
   *   }
   * }
   * }}}
   */
  def ContextAware[T](behavior: ActorContext[T] ⇒ Behavior[T]): Behavior[T] =
    FullTotal {
      case Sig(ctx, PreStart) ⇒ Behavior.preStart(behavior(ctx), ctx)
      case msg                ⇒ throw new IllegalStateException(s"ContextAware must receive PreStart as first message (got $msg)")
    }

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
  private[akka] val _unitFunction = (_: Any) ⇒ ()
  /**
   * INTERNAL API.
   */
  private[akka] def unitFunction[T, U] = _unhandledFunction.asInstanceOf[(T ⇒ Behavior[U])]

}
