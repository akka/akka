/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.pattern

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.dispatch.sysmsg._
import akka.util.{ Timeout, Unsafe }

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.language.implicitConversions
import scala.util.{ Failure, Success }

/**
 * This is what is used to complete a Future that is returned from an ask/? call,
 * when it times out.
 */
class AskTimeoutException(message: String, cause: Throwable) extends TimeoutException(message) {
  def this(message: String) = this(message, null: Throwable)
  override def getCause(): Throwable = cause
}

/**
 * This object contains implementation details of the “ask” pattern.
 */
trait AskSupport {

  /**
   * Import this implicit conversion to gain `?` and `ask` methods on
   * [[akka.actor.ActorRef]], which will defer to the
   * `ask(actorRef, message)(timeout)` method defined here.
   *
   * {{{
   * import akka.pattern.ask
   *
   * val future = actor ? message             // => ask(actor, message)
   * val future = actor ask message           // => ask(actor, message)
   * val future = actor.ask(message)(timeout) // => ask(actor, message)(timeout)
   * }}}
   *
   * All of the above use an implicit [[akka.util.Timeout]].
   */
  implicit def ask(actorRef: ActorRef): AskableActorRef = new AskableActorRef(actorRef)

  /**
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
   *   val f = ask(worker, request)(timeout)
   *   f.map { response =>
   *     EnrichedMessage(response)
   *   } pipeTo nextActor
   * }}}
   *
   */
  def ask(actorRef: ActorRef, message: Any)(implicit timeout: Timeout): Future[Any] =
    actorRef.internalAsk(message, timeout, ActorRef.noSender)
  def ask(actorRef: ActorRef, message: Any, sender: ActorRef)(implicit timeout: Timeout): Future[Any] =
    actorRef.internalAsk(message, timeout, sender)

  /**
   * Import this implicit conversion to gain `?` and `ask` methods on
   * [[akka.actor.ActorSelection]], which will defer to the
   * `ask(actorSelection, message)(timeout)` method defined here.
   *
   * {{{
   * import akka.pattern.ask
   *
   * val future = selection ? message             // => ask(selection, message)
   * val future = selection ask message           // => ask(selection, message)
   * val future = selection.ask(message)(timeout) // => ask(selection, message)(timeout)
   * }}}
   *
   * All of the above use an implicit [[akka.util.Timeout]].
   */
  implicit def ask(actorSelection: ActorSelection): AskableActorSelection = new AskableActorSelection(actorSelection)

  /**
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
   *   val f = ask(worker, request)(timeout)
   *   f.map { response =>
   *     EnrichedMessage(response)
   *   } pipeTo nextActor
   * }}}
   *
   */
  def ask(actorSelection: ActorSelection, message: Any)(implicit timeout: Timeout): Future[Any] =
    actorSelection.internalAsk(message, timeout, ActorRef.noSender)
  def ask(actorSelection: ActorSelection, message: Any, sender: ActorRef)(implicit timeout: Timeout): Future[Any] =
    actorSelection.internalAsk(message, timeout, sender)
}

/**
 * This object contains implementation details of the “ask” pattern,
 * which can be combined with "replyTo" pattern.
 */
trait ExplicitAskSupport {

  /**
   * Import this implicit conversion to gain `?` and `ask` methods on
   * [[akka.actor.ActorRef]], which will defer to the
   * `ask(actorRef, askSender => message)(timeout)` method defined here.
   *
   * {{{
   * import akka.pattern.ask
   *
   * // same as `ask(actor, askSender => Request(askSender))`
   * val future = actor ? { askSender => Request(askSender) }
   *
   * // same as `ask(actor, Request(_))`
   * val future = actor ? (Request(_))
   *
   * // same as `ask(actor, Request(_))(timeout)`
   * val future = actor ? (Request(_))(timeout)
   * }}}
   *
   * All of the above use a required implicit [[akka.util.Timeout]] and optional implicit
   * sender [[akka.actor.ActorRef]].
   */
  implicit def ask(actorRef: ActorRef): ExplicitlyAskableActorRef = new ExplicitlyAskableActorRef(actorRef)

  /**
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
   * val f = ask(worker, replyTo => Request(replyTo))(timeout)
   * f.map { response =>
   *   EnrichedMessage(response)
   * } pipeTo nextActor
   * }}}
   */
  def ask(actorRef: ActorRef, messageFactory: ActorRef ⇒ Any)(implicit timeout: Timeout): Future[Any] =
    actorRef.internalAsk(messageFactory, timeout, ActorRef.noSender)
  def ask(actorRef: ActorRef, messageFactory: ActorRef ⇒ Any, sender: ActorRef)(implicit timeout: Timeout): Future[Any] =
    actorRef.internalAsk(messageFactory, timeout, sender)

  /**
   * Import this implicit conversion to gain `?` and `ask` methods on
   * [[akka.actor.ActorSelection]], which will defer to the
   * `ask(actorSelection, message)(timeout)` method defined here.
   *
   * {{{
   * import akka.pattern.ask
   *
   * // same as `ask(selection, askSender => Request(askSender))`
   * val future = selection ? { askSender => Request(askSender) }
   *
   * // same as `ask(selection, Request(_))`
   * val future = selection ? (Request(_))
   *
   * // same as `ask(selection, Request(_))(timeout)`
   * val future = selection ? (Request(_))(timeout)
   * }}}
   *
   * All of the above use a required implicit [[akka.util.Timeout]] and optional implicit
   * sender [[akka.actor.ActorRef]].
   */
  implicit def ask(actorSelection: ActorSelection): ExplicitlyAskableActorSelection = new ExplicitlyAskableActorSelection(actorSelection)

  /**
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
   * val f = ask(worker, replyTo => Request(replyTo))(timeout)
   * f.map { response =>
   *   EnrichedMessage(response)
   * } pipeTo nextActor
   * }}}
   *
   */
  def ask(actorSelection: ActorSelection, messageFactory: ActorRef ⇒ Any)(implicit timeout: Timeout): Future[Any] =
    actorSelection.internalAsk(messageFactory, timeout, ActorRef.noSender)
  def ask(actorSelection: ActorSelection, messageFactory: ActorRef ⇒ Any, sender: ActorRef)(implicit timeout: Timeout): Future[Any] =
    actorSelection.internalAsk(messageFactory, timeout, sender)
}

object AskableActorRef {
  /**
   * INTERNAL API: for binary compatibility
   */
  private[pattern] def ask$extension(actorRef: ActorRef, message: Any, timeout: Timeout): Future[Any] =
    actorRef.internalAsk(message, timeout, ActorRef.noSender)

  /**
   * INTERNAL API: for binary compatibility
   */
  private[pattern] def $qmark$extension(actorRef: ActorRef, message: Any, timeout: Timeout): Future[Any] =
    actorRef.internalAsk(message, timeout, ActorRef.noSender)
}

/*
 * Implementation class of the “ask” pattern enrichment of ActorRef
 */
final class AskableActorRef(val actorRef: ActorRef) extends AnyVal {

  /**
   * INTERNAL API: for binary compatibility
   */
  protected def ask(message: Any, timeout: Timeout): Future[Any] =
    internalAsk(message, timeout, ActorRef.noSender)

  def ask(message: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAsk(message, timeout, sender)

  /**
   * INTERNAL API: for binary compatibility
   */
  protected def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    internalAsk(message, timeout, ActorRef.noSender)

  def ?(message: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAsk(message, timeout, sender)

  /**
   * INTERNAL API: for binary compatibility
   */
  private[pattern] def internalAsk(message: Any, timeout: Timeout, sender: ActorRef) = actorRef match {
    case ref: InternalActorRef if ref.isTerminated ⇒
      actorRef ! message
      Future.failed[Any](new AskTimeoutException(s"""Recipient[$actorRef] had already been terminated. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
    case ref: InternalActorRef ⇒
      if (timeout.duration.length <= 0)
        Future.failed[Any](new IllegalArgumentException(s"""Timeout length must not be negative, question not sent to [$actorRef]. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
      else {
        val a = PromiseActorRef(ref.provider, timeout, targetName = actorRef, message.getClass.getName, sender)
        actorRef.tell(message, a)
        a.result.future
      }
    case _ ⇒ Future.failed[Any](new IllegalArgumentException(s"""Unsupported recipient ActorRef type, question not sent to [$actorRef]. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
  }

}

/*
 * Implementation class of the “ask” with explicit sender pattern enrichment of ActorRef
 */
final class ExplicitlyAskableActorRef(val actorRef: ActorRef) extends AnyVal {

  def ask(message: ActorRef ⇒ Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAsk(message, timeout, sender)

  def ?(message: ActorRef ⇒ Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAsk(message, timeout, sender)

  /**
   * INTERNAL API: for binary compatibility
   */
  private[pattern] def internalAsk(messageFactory: ActorRef ⇒ Any, timeout: Timeout, sender: ActorRef): Future[Any] = actorRef match {
    case ref: InternalActorRef if ref.isTerminated ⇒
      val message = messageFactory(ref.provider.deadLetters)
      actorRef ! message
      Future.failed[Any](new AskTimeoutException(s"""Recipient[$actorRef] had already been terminated. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
    case ref: InternalActorRef ⇒
      if (timeout.duration.length <= 0) {
        val message = messageFactory(ref.provider.deadLetters)
        Future.failed[Any](new IllegalArgumentException(s"""Timeout length must not be negative, question not sent to [$actorRef]. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
      } else {
        val a = PromiseActorRef(ref.provider, timeout, targetName = actorRef, "unknown", sender)
        val message = messageFactory(a)
        a.messageClassName = message.getClass.getName
        actorRef.tell(message, a)
        a.result.future
      }
    case _ if sender eq null ⇒
      Future.failed[Any](new IllegalArgumentException(s"""No recipient provided, question not sent to [$actorRef]."""))
    case _ ⇒
      val message = messageFactory(sender.asInstanceOf[InternalActorRef].provider.deadLetters)
      Future.failed[Any](new IllegalArgumentException(s"""Unsupported recipient ActorRef type, question not sent to [$actorRef]. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
  }
}

object AskableActorSelection {
  /**
   * INTERNAL API: for binary compatibility
   */
  private[pattern] def ask$extension(actorSel: ActorSelection, message: Any, timeout: Timeout): Future[Any] =
    actorSel.internalAsk(message, timeout, ActorRef.noSender)

  /**
   * INTERNAL API: for binary compatibility
   */
  private[pattern] def $qmark$extension(actorSel: ActorSelection, message: Any, timeout: Timeout): Future[Any] =
    actorSel.internalAsk(message, timeout, ActorRef.noSender)
}

/*
 * Implementation class of the “ask” pattern enrichment of ActorSelection
 */
final class AskableActorSelection(val actorSel: ActorSelection) extends AnyVal {

  /**
   * INTERNAL API: for binary compatibility
   */
  protected def ask(message: Any, timeout: Timeout): Future[Any] =
    internalAsk(message, timeout, ActorRef.noSender)

  def ask(message: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAsk(message, timeout, sender)

  /**
   * INTERNAL API: for binary compatibility
   */
  protected def ?(message: Any)(implicit timeout: Timeout): Future[Any] =
    internalAsk(message, timeout, ActorRef.noSender)

  def ?(message: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAsk(message, timeout, sender)

  /**
   * INTERNAL API: for binary compatibility
   */
  private[pattern] def internalAsk(message: Any, timeout: Timeout, sender: ActorRef): Future[Any] = actorSel.anchor match {
    case ref: InternalActorRef ⇒
      if (timeout.duration.length <= 0)
        Future.failed[Any](
          new IllegalArgumentException(s"""Timeout length must not be negative, question not sent to [$actorSel]. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
      else {
        val a = PromiseActorRef(ref.provider, timeout, targetName = actorSel, message.getClass.getName, sender)
        actorSel.tell(message, a)
        a.result.future
      }
    case _ ⇒ Future.failed[Any](new IllegalArgumentException(s"""Unsupported recipient ActorRef type, question not sent to [$actorSel]. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
  }
}

/*
 * Implementation class of the “ask” with explicit sender pattern enrichment of ActorSelection
 */
final class ExplicitlyAskableActorSelection(val actorSel: ActorSelection) extends AnyVal {

  def ask(message: ActorRef ⇒ Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAsk(message, timeout, sender)

  def ?(message: ActorRef ⇒ Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAsk(message, timeout, sender)

  /**
   * INTERNAL API: for binary compatibility
   */
  private[pattern] def internalAsk(messageFactory: ActorRef ⇒ Any, timeout: Timeout, sender: ActorRef): Future[Any] = actorSel.anchor match {
    case ref: InternalActorRef ⇒
      if (timeout.duration.length <= 0) {
        val message = messageFactory(ref.provider.deadLetters)
        Future.failed[Any](
          new IllegalArgumentException(s"""Timeout length must not be negative, question not sent to [$actorSel]. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
      } else {
        val a = PromiseActorRef(ref.provider, timeout, targetName = actorSel, "unknown", sender)
        val message = messageFactory(a)
        a.messageClassName = message.getClass.getName
        actorSel.tell(message, a)
        a.result.future
      }
    case _ if sender eq null ⇒
      Future.failed[Any](new IllegalArgumentException(s"""No recipient provided, question not sent to [$actorSel]."""))
    case _ ⇒
      val message = messageFactory(sender.asInstanceOf[InternalActorRef].provider.deadLetters)
      Future.failed[Any](new IllegalArgumentException(s"""Unsupported recipient ActorRef type, question not sent to [$actorSel]. Sender[$sender] sent the message of type "${message.getClass.getName}"."""))
  }
}

/**
 * Akka private optimized representation of the temporary actor spawned to
 * receive the reply to an "ask" operation.
 *
 * INTERNAL API
 */
private[akka] final class PromiseActorRef private (val provider: ActorRefProvider, val result: Promise[Any], _mcn: String)
  extends MinimalActorRef {
  import AbstractPromiseActorRef.{ stateOffset, watchedByOffset }
  import PromiseActorRef._

  @deprecated("Use the full constructor", "2.4")
  def this(provider: ActorRefProvider, result: Promise[Any]) = this(provider, result, "unknown")

  // This is necessary for weaving the PromiseActorRef into the asked message, i.e. the replyTo pattern.
  @volatile var messageClassName = _mcn

  /**
   * As an optimization for the common (local) case we only register this PromiseActorRef
   * with the provider when the `path` member is actually queried, which happens during
   * serialization (but also during a simple call to `toString`, `equals` or `hashCode`!).
   *
   * Defined states:
   * null                  => started, path not yet created
   * Registering           => currently creating temp path and registering it
   * path: ActorPath       => path is available and was registered
   * StoppedWithPath(path) => stopped, path available
   * Stopped               => stopped, path not yet created
   */
  @volatile
  private[this] var _stateDoNotCallMeDirectly: AnyRef = _

  @volatile
  private[this] var _watchedByDoNotCallMeDirectly: Set[ActorRef] = ActorCell.emptyActorRefSet

  @inline
  private[this] def watchedBy: Set[ActorRef] = Unsafe.instance.getObjectVolatile(this, watchedByOffset).asInstanceOf[Set[ActorRef]]

  @inline
  private[this] def updateWatchedBy(oldWatchedBy: Set[ActorRef], newWatchedBy: Set[ActorRef]): Boolean =
    Unsafe.instance.compareAndSwapObject(this, watchedByOffset, oldWatchedBy, newWatchedBy)

  @tailrec // Returns false if the Promise is already completed
  private[this] final def addWatcher(watcher: ActorRef): Boolean = watchedBy match {
    case null  ⇒ false
    case other ⇒ updateWatchedBy(other, other + watcher) || addWatcher(watcher)
  }

  @tailrec
  private[this] final def remWatcher(watcher: ActorRef): Unit = watchedBy match {
    case null  ⇒ ()
    case other ⇒ if (!updateWatchedBy(other, other - watcher)) remWatcher(watcher)
  }

  @tailrec
  private[this] final def clearWatchers(): Set[ActorRef] = watchedBy match {
    case null  ⇒ ActorCell.emptyActorRefSet
    case other ⇒ if (!updateWatchedBy(other, null)) clearWatchers() else other
  }

  @inline
  private[this] def state: AnyRef = Unsafe.instance.getObjectVolatile(this, stateOffset)

  @inline
  private[this] def updateState(oldState: AnyRef, newState: AnyRef): Boolean =
    Unsafe.instance.compareAndSwapObject(this, stateOffset, oldState, newState)

  @inline
  private[this] def setState(newState: AnyRef): Unit = Unsafe.instance.putObjectVolatile(this, stateOffset, newState)

  override def getParent: InternalActorRef = provider.tempContainer

  def internalCallingThreadExecutionContext: ExecutionContext =
    provider.guardian.underlying.systemImpl.internalCallingThreadExecutionContext

  /**
   * Contract of this method:
   * Must always return the same ActorPath, which must have
   * been registered if we haven't been stopped yet.
   */
  @tailrec
  def path: ActorPath = state match {
    case null ⇒
      if (updateState(null, Registering)) {
        var p: ActorPath = null
        try {
          p = provider.tempPath()
          provider.registerTempActor(this, p)
          p
        } finally { setState(p) }
      } else path
    case p: ActorPath       ⇒ p
    case StoppedWithPath(p) ⇒ p
    case Stopped ⇒
      // even if we are already stopped we still need to produce a proper path
      updateState(Stopped, StoppedWithPath(provider.tempPath()))
      path
    case Registering ⇒ path // spin until registration is completed
  }

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = state match {
    case Stopped | _: StoppedWithPath ⇒ provider.deadLetters ! message
    case _ ⇒
      if (message == null) throw new InvalidMessageException("Message is null")
      if (!(result.tryComplete(
        message match {
          case Status.Success(r) ⇒ Success(r)
          case Status.Failure(f) ⇒ Failure(f)
          case other             ⇒ Success(other)
        }))) provider.deadLetters ! message
  }

  override def sendSystemMessage(message: SystemMessage): Unit = message match {
    case _: Terminate                      ⇒ stop()
    case DeathWatchNotification(a, ec, at) ⇒ this.!(Terminated(a)(existenceConfirmed = ec, addressTerminated = at))
    case Watch(watchee, watcher) ⇒
      if (watchee == this && watcher != this) {
        if (!addWatcher(watcher))
          // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          watcher.sendSystemMessage(DeathWatchNotification(watchee, existenceConfirmed = true, addressTerminated = false))
      } else System.err.println("BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, this))
    case Unwatch(watchee, watcher) ⇒
      if (watchee == this && watcher != this) remWatcher(watcher)
      else System.err.println("BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, this))
    case _ ⇒
  }

  @deprecated("Use context.watch(actor) and receive Terminated(actor)", "2.2")
  override private[akka] def isTerminated: Boolean = state match {
    case Stopped | _: StoppedWithPath ⇒ true
    case _                            ⇒ false
  }

  @tailrec
  override def stop(): Unit = {
    def ensureCompleted(): Unit = {
      result tryComplete ActorStopResult
      val watchers = clearWatchers()
      if (!watchers.isEmpty) {
        watchers foreach { watcher ⇒
          // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          watcher.asInstanceOf[InternalActorRef]
            .sendSystemMessage(DeathWatchNotification(this, existenceConfirmed = true, addressTerminated = false))
        }
      }
    }
    state match {
      case null ⇒ // if path was never queried nobody can possibly be watching us, so we don't have to publish termination either
        if (updateState(null, Stopped)) ensureCompleted() else stop()
      case p: ActorPath ⇒
        if (updateState(p, StoppedWithPath(p))) { try ensureCompleted() finally provider.unregisterTempActor(p) } else stop()
      case Stopped | _: StoppedWithPath ⇒ // already stopped
      case Registering                  ⇒ stop() // spin until registration is completed before stopping
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] object PromiseActorRef {
  private case object Registering
  private case object Stopped
  private final case class StoppedWithPath(path: ActorPath)

  private val ActorStopResult = Failure(new ActorKilledException("Stopped"))

  def apply(provider: ActorRefProvider, timeout: Timeout, targetName: Any, messageClassName: String, sender: ActorRef = Actor.noSender): PromiseActorRef = {
    val result = Promise[Any]()
    val scheduler = provider.guardian.underlying.system.scheduler
    val a = new PromiseActorRef(provider, result, messageClassName)
    implicit val ec = a.internalCallingThreadExecutionContext
    val f = scheduler.scheduleOnce(timeout.duration) {
      result tryComplete Failure(
        new AskTimeoutException(s"""Ask timed out on [$targetName] after [${timeout.duration.toMillis} ms]. Sender[$sender] sent message of type "${a.messageClassName}"."""))
    }
    result.future onComplete { _ ⇒ try a.stop() finally f.cancel() }
    a
  }

  @deprecated("Use apply with messageClassName and sender parameters", "2.4")
  def apply(provider: ActorRefProvider, timeout: Timeout, targetName: String): PromiseActorRef =
    apply(provider, timeout, targetName, "unknown", Actor.noSender)
}
