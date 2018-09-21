/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import akka.actor.typed._
import akka.actor.typed.internal._
import akka.actor.typed.internal.adapter.AbstractLogger
import akka.actor.testkit.typed.scaladsl.TestInbox
import akka.actor.{ ActorPath, InvalidMessageException }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.util.{ Helpers, OptionVal }
import akka.{ actor ⇒ untyped }
import java.util.concurrent.ThreadLocalRandom.{ current ⇒ rnd }

import scala.collection.JavaConverters._
import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorRefProvider

/**
 * INTERNAL API
 *
 * A local synchronous ActorRef that invokes the given function for every message send.
 * This reference cannot watch other references.
 */
@InternalApi
private[akka] final class FunctionRef[-T](
  override val path: ActorPath,
  send:              (T, FunctionRef[T]) ⇒ Unit)
  extends ActorRef[T] with ActorRefImpl[T] with InternalRecipientRef[T] {

  override def tell(msg: T): Unit = {
    if (msg == null) throw InvalidMessageException("[null] is not an allowed message")
    send(msg, this)
  }

  // impl ActorRefImpl
  override def sendSystem(signal: SystemMessage): Unit = ()
  // impl ActorRefImpl
  override def isLocal = true

  // impl InternalRecipientRef, ask not supported
  override def provider: ActorRefProvider = throw new UnsupportedOperationException("no provider")
  // impl InternalRecipientRef
  def isTerminated: Boolean = false
}

final case class CapturedLogEvent(logLevel: LogLevel, message: String,
                                  cause:  OptionVal[Throwable],
                                  marker: OptionVal[LogMarker],
                                  mdc:    Map[String, Any]) {
  def getMdc: java.util.Map[String, Any] = mdc.asJava
}

/**
 * INTERNAL API
 *
 * Captures log events for test inspection
 */
@InternalApi private[akka] final class StubbedLogger extends AbstractLogger {

  private var logBuffer: List[CapturedLogEvent] = Nil

  override def isErrorEnabled: Boolean = true
  override def isWarningEnabled: Boolean = true
  override def isInfoEnabled: Boolean = true
  override def isDebugEnabled: Boolean = true

  override private[akka] def notifyError(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit =
    logBuffer = CapturedLogEvent(Logging.ErrorLevel, message, cause, marker, mdc) :: logBuffer
  override private[akka] def notifyWarning(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit =
    logBuffer = CapturedLogEvent(Logging.WarningLevel, message, OptionVal.None, marker, mdc) :: logBuffer

  override private[akka] def notifyInfo(message: String, marker: OptionVal[LogMarker]): Unit =
    logBuffer = CapturedLogEvent(Logging.InfoLevel, message, OptionVal.None, marker, mdc) :: logBuffer

  override private[akka] def notifyDebug(message: String, marker: OptionVal[LogMarker]): Unit =
    logBuffer = CapturedLogEvent(Logging.DebugLevel, message, OptionVal.None, marker, mdc) :: logBuffer

  def logEntries: List[CapturedLogEvent] = logBuffer.reverse
  def clearLog(): Unit = logBuffer = Nil

  override def withMdc(mdc: Map[String, Any]): Logger = {
    // we need to decorate to get log entries ending up the same logBuffer
    val withMdc = new StubbedLoggerWithMdc(this)
    withMdc.mdc = mdc
    withMdc
  }
}

@InternalApi private[akka] final class StubbedLoggerWithMdc(actual: StubbedLogger) extends AbstractLogger {
  override def isErrorEnabled: Boolean = actual.isErrorEnabled
  override def isWarningEnabled: Boolean = actual.isWarningEnabled
  override def isInfoEnabled: Boolean = actual.isInfoEnabled
  override def isDebugEnabled: Boolean = actual.isDebugEnabled
  override def withMdc(mdc: Map[String, Any]): Logger = actual.withMdc(mdc)

  override private[akka] def notifyError(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit = {
    val original = actual.mdc
    actual.mdc = mdc
    actual.notifyError(message, cause, marker)
    actual.mdc = original
  }

  override private[akka] def notifyWarning(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit = {
    val original = actual.mdc
    actual.mdc = mdc
    actual.notifyWarning(message, cause, marker)
    actual.mdc = original
  }

  override private[akka] def notifyInfo(message: String, marker: OptionVal[LogMarker]): Unit = {
    val original = actual.mdc
    actual.mdc = mdc
    actual.notifyInfo(message, marker)
    actual.mdc = original
  }

  override private[akka] def notifyDebug(message: String, marker: OptionVal[LogMarker]): Unit = {
    val original = actual.mdc
    actual.mdc = mdc
    actual.notifyDebug(message, marker)
    actual.mdc = original
  }

}

/**
 * INTERNAL API
 *
 * An [[ActorContext]] for synchronous execution of a [[Behavior]] that
 * provides only stubs for the effects an Actor can perform and replaces
 * created child Actors by a synchronous Inbox (see `Inbox.sync`).
 */
@InternalApi private[akka] class StubbedActorContext[T](
  val path: ActorPath) extends ActorContextImpl[T] {

  def this(name: String) = {
    this(TestInbox.address / name withUid rnd().nextInt())
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val selfInbox = new TestInboxImpl[T](path)

  override val self = selfInbox.ref
  override val system = new ActorSystemStub("StubbedActorContext")
  private var _children = TreeMap.empty[String, BehaviorTestKitImpl[_]]
  private val childName = Iterator from 0 map (Helpers.base64(_))
  private val loggingAdapter = new StubbedLogger

  override def children: Iterable[ActorRef[Nothing]] = _children.values map (_.ctx.self)
  def childrenNames: Iterable[String] = _children.keys

  override def child(name: String): Option[ActorRef[Nothing]] = _children get name map (_.ctx.self)

  override def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U] = {
    val btk = new BehaviorTestKitImpl[U](path / childName.next() withUid rnd().nextInt(), behavior)
    _children += btk.ctx.self.path.name → btk
    btk.ctx.self
  }
  override def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U] =
    _children get name match {
      case Some(_) ⇒ throw untyped.InvalidActorNameException(s"actor name $name is already taken")
      case None ⇒
        val btk = new BehaviorTestKitImpl[U](path / name withUid rnd().nextInt(), behavior)
        _children += name → btk
        btk.ctx.self
    }

  /**
   * Do not actually stop the child inbox, only simulate the liveness check.
   * Removal is asynchronous, explicit removeInbox is needed from outside afterwards.
   */
  override def stop[U](child: ActorRef[U]): Unit = {
    if (child.path.parent != self.path) throw new IllegalArgumentException(
      "Only direct children of an actor can be stopped through the actor context, " +
        s"but [$child] is not a child of [$self]. Stopping other actors has to be expressed as " +
        "an explicit stop message that the actor accepts.")
    else {
      _children -= child.path.name
    }
  }
  override def watch[U](other: ActorRef[U]): Unit = ()
  override def watchWith[U](other: ActorRef[U], msg: T): Unit = ()
  override def unwatch[U](other: ActorRef[U]): Unit = ()
  override def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = ()
  override def cancelReceiveTimeout(): Unit = ()

  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): untyped.Cancellable = new untyped.Cancellable {
    override def cancel() = false
    override def isCancelled = true
  }

  // TODO allow overriding of this
  override def executionContext: ExecutionContextExecutor = system.executionContext

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def internalSpawnMessageAdapter[U](f: U ⇒ T, name: String): ActorRef[U] = {

    val n = if (name != "") s"${childName.next()}-$name" else childName.next()
    val p = path / n withUid rnd().nextInt()
    val i = new BehaviorTestKitImpl[U](p, Behavior.ignore)
    _children += p.name → i

    new FunctionRef[U](
      p,
      (msg, _) ⇒ { val m = f(msg); if (m != null) { selfInbox.ref ! m; i.selfInbox.ref ! msg } })
  }

  /**
   * Retrieve the inbox representing the given child actor. The passed ActorRef must be one that was returned
   * by one of the spawn methods earlier.
   */
  def childInbox[U](child: ActorRef[U]): TestInboxImpl[U] = {
    val btk = _children(child.path.name)
    if (btk.ctx.self != child) throw new IllegalArgumentException(s"$child is not a child of $this")
    btk.ctx.selfInbox.as[U]
  }

  /**
   * Retrieve the BehaviorTestKit for the given child actor. The passed ActorRef must be one that was returned
   * by one of the spawn methods earlier.
   */
  def childTestKit[U](child: ActorRef[U]): BehaviorTestKitImpl[U] = {
    val btk = _children(child.path.name)
    if (btk.ctx.self != child) throw new IllegalArgumentException(s"$child is not a child of $this")
    btk.as
  }

  /**
   * Retrieve the inbox representing the child actor with the given name.
   */
  def childInbox[U](name: String): Option[TestInboxImpl[U]] = _children.get(name).map(_.ctx.selfInbox.as[U])

  /**
   * Remove the given inbox from the list of children, for example after
   * having simulated its termination.
   */
  def removeChildInbox(child: ActorRef[Nothing]): Unit = _children -= child.path.name

  override def toString: String = s"Inbox($self)"

  override def log: Logger = loggingAdapter

  /**
   * The log entries logged through ctx.log.{debug, info, warn, error} are captured and can be inspected through
   * this method.
   */
  def logEntries: List[CapturedLogEvent] = loggingAdapter.logEntries

  /**
   * Clear the log entries
   */
  def clearLog(): Unit = loggingAdapter.clearLog()
}
