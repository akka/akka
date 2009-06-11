/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.actor

import java.util.concurrent.{ConcurrentSkipListSet, TimeUnit}
import kernel.reactor._

sealed abstract class LifecycleMessage
//case class Init(config: AnyRef) extends LifecycleMessage
//case class Shutdown(reason: AnyRef) extends LifecycleMessage
case class Stop(reason: AnyRef) extends LifecycleMessage
//case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends LifecycleMessage
case class Exit(dead: Actor, killer: Throwable) extends LifecycleMessage
case object Restart extends LifecycleMessage

sealed abstract class DispatcherType
case object EventBased extends DispatcherType
case object ThreadBased extends DispatcherType

class ActorMessageHandler(val actor: Actor) extends MessageHandler {
  def handle(handle: MessageHandle) = actor.handle(handle.message, handle.future)
}

trait Actor {  
  private[this] val linkedActors = new ConcurrentSkipListSet[Actor]

  private[this] var dispatcher: MessageDispatcher = _
  private[this] var mailbox: MessageQueue = _
  private[this] var senderFuture: Option[CompletableFutureResult] = None
  @volatile private var isRunning: Boolean = false

  private var hotswap: Option[PartialFunction[Any, Unit]] = None
  private var config: Option[AnyRef] = None

  // ====================================
  // ==== USER CALLBACKS TO OVERRIDE ====
  // ====================================

  /**
   * Set dispatcher type to either EventBased or ThreadBased.
   * Default is EventBased.
   */
  protected[this] var dispatcherType: DispatcherType = EventBased
  
  /**
   * Set trapExit to true if actor should be able to trap linked actors exit messages.
   */
  @volatile protected[this] var trapExit: Boolean = false

  /**
   * Partial function implementing the server logic.
   * To be implemented by subclassing server.
   * <p/>
   * Example code:
   * <pre>
   *   def receive: PartialFunction[Any, Unit] = {
   *     case Ping =>
   *       println("got a ping")
   *       reply("pong")
   *
   *     case OneWay =>
   *       println("got a oneway")
   *
   *     case _ =>
   *       println("unknown message, ignoring")
   *   }
   * </pre>
   */
  protected def receive: PartialFunction[Any, Unit]

  /**
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def restart(config: Option[AnyRef])

  /**
   * Optional callback method that is called during initialization.
   * To be implemented by subclassing actor.
   */
  protected def init(config: AnyRef) {}

  /**
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  protected def shutdown(reason: AnyRef) {}

  // =============
  // ==== API ====
  // =============

  def !(message: AnyRef) =
    if (isRunning) mailbox.append(new MessageHandle(this, message, new NullFutureResult))
    else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  def !![T](message: AnyRef)(implicit timeout: Long): Option[T] = if (isRunning) {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout)
    future.await_?
    getResultOrThrowException(future)
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  def !?[T](message: AnyRef): Option[T] = if (isRunning) {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, 0)
    future.await_!
    getResultOrThrowException(future)
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  def link(actor: Actor) =
    if (isRunning) linkedActors.add(actor)
    else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  def unlink(actor: Actor) =
    if (isRunning) linkedActors.remove(actor)
    else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  def start = synchronized {
    if (!isRunning) {
      dispatcherType match {
        case EventBased =>
          dispatcher = new EventBasedSingleThreadDispatcher
        case ThreadBased =>
          dispatcher = new EventBasedThreadPoolDispatcher
      }
      mailbox = dispatcher.messageQueue
      dispatcher.registerHandler(this, new ActorMessageHandler(this))
      dispatcher.start
      isRunning = true
    }
  }

  def stop =
    if (isRunning) {
      this ! Stop("Actor gracefully stopped")
      dispatcher.unregisterHandler(this)
      isRunning = false
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  protected def reply(message: AnyRef) = senderFuture match {
    case None => throw new IllegalStateException("No sender future in scope, can't reply")
    case Some(future) => future.completeWithResult(message)
  }

  // ================================
  // ==== IMPLEMENTATION DETAILS ====
  // ================================

  private def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: AnyRef, timeout: Long): CompletableFutureResult = {
    val future = new DefaultCompletableFutureResult(timeout)
    mailbox.append(new MessageHandle(this, message, future))
    future
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) throw future.exception.get
    else future.result.asInstanceOf[Option[T]]

  private[kernel] def handle(message: AnyRef, future: CompletableFutureResult) = {
    try {
      senderFuture = Some(future)
      if (base.isDefinedAt(message)) base(message)
      else throw new IllegalArgumentException("No handler matching message [" + message + "] in actor [" + this.getClass.getName + "]")
    } catch {
      case e =>
        future.completeWithException(e)
        handleFailure(this, e)
    }
/*
    try {
      val result = message.asInstanceOf[Invocation].joinpoint.proceed
      future.completeWithResult(result)
    } catch {
      case e: Exception => future.completeWithException(e)
    }
*/
  }

  private def base: PartialFunction[Any, Unit] = lifeCycle orElse (hotswap getOrElse receive)

  private val lifeCycle: PartialFunction[Any, Unit] = {
    case Init(config) =>       init(config)
    case HotSwap(code) =>      hotswap = code
    case Restart =>            restart(config)
    case Stop(reason) =>       shutdown(reason); exit
    case Exit(dead, reason) => handleFailure(dead, reason)
  }

  private[this] def handleFailure(dead: Actor, e: Throwable) = {
    if (trapExit) {
      restartLinkedActors
      scheduleRestart
    } else linkedActors.toArray.toList.asInstanceOf[List[Actor]].foreach(_ ! Exit(this, e))
  }

  private[this] def restartLinkedActors = linkedActors.toArray.toList.asInstanceOf[List[Actor]].foreach(_.scheduleRestart)

  private[Actor] def scheduleRestart = mailbox.prepend(new MessageHandle(this, Restart, new NullFutureResult))
}