/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.actor

import java.util.concurrent.{CopyOnWriteArraySet, TimeUnit}

import kernel.reactor._
import kernel.config.ScalaConfig._
import kernel.nio.{NettyClient, RemoteRequest}
import kernel.stm.Transaction
import kernel.util.Logging
import kernel.util.Helpers._

sealed abstract class LifecycleMessage
case class Init(config: AnyRef) extends LifecycleMessage
case class Stop(reason: AnyRef) extends LifecycleMessage
case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends LifecycleMessage
case class Restart(reason: AnyRef) extends LifecycleMessage
case class Exit(dead: Actor, killer: Throwable) extends LifecycleMessage

sealed abstract class DispatcherType
object DispatcherType {
  case object EventBasedThreadPooledProxyInvokingDispatcher extends DispatcherType
  case object EventBasedSingleThreadingDispatcher extends DispatcherType
  case object EventBasedThreadPoolingDispatcher extends DispatcherType
  case object ThreadBasedDispatcher extends DispatcherType
}

class ActorMessageHandler(val actor: Actor) extends MessageHandler {
  def handle(handle: MessageHandle) = actor.handle(handle.message, handle.future)
}

trait Actor extends Logging {
  var timeout: Long = 5000L
  var isRemote = false
  
  @volatile private[this] var isRunning: Boolean = false
  protected[this] var id: String = super.toString
  protected[this] var dispatcher: MessageDispatcher = _
  protected[this] var senderFuture: Option[CompletableFutureResult] = None
  protected[this] val linkedActors = new CopyOnWriteArraySet[Actor]

  protected[actor] var mailbox: MessageQueue = _
  protected[actor] var supervisor: Option[Actor] = None
  protected[actor] var lifeCycleConfig: Option[LifeCycle] = None

  private var hotswap: Option[PartialFunction[Any, Unit]] = None
  private var config: Option[AnyRef] = None

  // ====================================
  // ==== USER CALLBACKS TO OVERRIDE ====
  // ====================================

  /**
   * TODO: document
   */
  protected var faultHandler: Option[FaultHandlingStrategy] = None

  /**
   * Set dispatcher type to either ThreadBasedDispatcher, EventBasedSingleThreadingDispatcher or EventBasedThreadPoolingDispatcher.
   * Default is EventBasedThreadPoolingDispatcher.
   */
  protected[this] var dispatcherType: DispatcherType = DispatcherType.EventBasedThreadPoolingDispatcher
  
  /**
   * Set trapExit to true if actor should be able to trap linked actors exit messages.
   */
  protected[this] var trapExit: Boolean = false

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
   * Optional callback method that is called during initialization.
   * To be implemented by subclassing actor.
   */
  protected def init(config: AnyRef) {}

  /**
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def preRestart(reason: AnyRef, config: Option[AnyRef]) {}

  /**
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {}

  /**
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  protected def shutdown(reason: AnyRef) {}

  // =============
  // ==== API ====
  // =============

  def !(message: AnyRef) = if (isRunning) {
    if (isRemote) NettyClient.send(new RemoteRequest(true, message, null, this.getClass.getName, null, true, false))
    else mailbox.append(new MessageHandle(this, message, new NullFutureResult))
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  
  def !![T](message: AnyRef, timeout: Long): Option[T] = if (isRunning) {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout)
    future.await_?
    getResultOrThrowException(future)
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  def !![T](message: AnyRef): Option[T] = !![T](message, timeout)

  def !?[T](message: AnyRef): T = if (isRunning) {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, 0)
    future.await_!
    getResultOrThrowException(future).get
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  protected[this] def reply(message: AnyRef) = senderFuture match {
    case None => throw new IllegalStateException("No sender future in scope, can't reply. Have you used '!' (async, fire-and-forget)? If so, switch to '!!' which will return a future to wait on." )
    case Some(future) => future.completeWithResult(message)
  }

  // FIXME can be deadlock prone if cyclic linking? - HOWTO?
  protected[this] def link(actor: Actor) = synchronized { actor.synchronized {
    if (isRunning) {
      linkedActors.add(actor)
      if (actor.supervisor.isDefined) throw new IllegalStateException("Actor can only have one supervisor [" + actor + "], e.g. link(actor) fails")
      actor.supervisor = Some(this)
      log.debug("Linking actor [%s] to actor [%s]", actor, this)
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }}

    // FIXME can be deadlock prone if cyclic linking? - HOWTO?
  protected[this] def unlink(actor: Actor) = synchronized { actor.synchronized {
    if (isRunning) {
      if (!linkedActors.contains(actor)) throw new IllegalStateException("Actor [" + actor + "] is not a linked actor, can't unlink")
      linkedActors.remove(actor)
      actor.supervisor = None
      log.debug("Unlinking actor [%s] from actor [%s]", actor, this)
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }}
  
  def start = synchronized  {
    if (!isRunning) {
      dispatcherType match {
        case DispatcherType.EventBasedSingleThreadingDispatcher =>
          dispatcher = new EventBasedSingleThreadDispatcher
        case DispatcherType.EventBasedThreadPoolingDispatcher =>
          dispatcher = new EventBasedThreadPoolDispatcher
        case DispatcherType.ThreadBasedDispatcher =>
          dispatcher = new ThreadBasedDispatcher
        case DispatcherType.EventBasedThreadPooledProxyInvokingDispatcher =>
          dispatcher = new ProxyMessageDispatcher
      }
      mailbox = dispatcher.messageQueue
      dispatcher.registerHandler(this, new ActorMessageHandler(this))
      dispatcher.start
      isRunning = true
    }
  }

  def stop = synchronized {
    if (isRunning) {
      dispatcher.unregisterHandler(this)
      isRunning = false
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }
  
  /**
   * Atomically start and link actor.
   */
  def spawnLink(actor: Actor) = actor.synchronized {
    actor.start
    link(actor)
  }

  /**
   * Atomically start and link a remote actor.
   */
  def spawnLinkRemote(actor: Actor) = actor.synchronized {
    actor.makeRemote
    actor.start
    link(actor)
  }

  def spawn(actorClass: Class[_]) = {

    // FIXME: should pass in dispatcher etc. - inherit
  }

  def spawnRemote(actorClass: Class[_]) = {
  }

  def spawnLink(actorClass: Class[_]) = {
  }

  def spawnLinkRemote(actorClass: Class[_]) = {
  }

  def makeRemote = isRemote = true

  // ================================
  // ==== IMPLEMENTATION DETAILS ====
  // ================================

  private[kernel] def handle(message: AnyRef, future: CompletableFutureResult) = synchronized {
    try {
      senderFuture = Some(future)
      if (base.isDefinedAt(message)) base(message)
      else throw new IllegalArgumentException("No handler matching message [" + message + "] in actor [" + this.getClass.getName + "]")
    } catch {
      case e =>
        if (supervisor.isDefined) supervisor.get ! Exit(this, e)
        future.completeWithException(this, e)
    }
  }

  private def postMessageToMailboxAndCreateFutureResultWithTimeout(message: AnyRef, timeout: Long): CompletableFutureResult = {
    if (isRemote) NettyClient.send(new RemoteRequest(true, message, null, this.getClass.getName, null, false, false))
    else {
      val future = new DefaultCompletableFutureResult(timeout)
      mailbox.append(new MessageHandle(this, message, future))
      future
    }
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (isRemote) getRemoteResultOrThrowException(future)
    else {
      if (future.exception.isDefined) {
        val (_, cause) = future.exception.get
        throw cause
      } else {
        future.result.asInstanceOf[Option[T]]
      }
    }

  // FIXME: UGLY - Either: make the remote tx work
  // FIXME:        OR: remove this method along with the tx tuple making in NettyClient.messageReceived and ActiveObject.getResultOrThrowException
  private def getRemoteResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      throw cause // throw new TransactionAwareException(cause, activeTx)
    } else {
      if (future.result.isDefined) {
        val (res, tx) = future.result.get.asInstanceOf[Tuple2[Option[T], Option[Transaction]]]
        res
      } else None
    }

  private def base: PartialFunction[Any, Unit] = lifeCycle orElse (hotswap getOrElse receive)

  private val lifeCycle: PartialFunction[Any, Unit] = {
    case Init(config) =>       init(config)
    case HotSwap(code) =>      hotswap = code
    case Restart(reason) =>    restart(reason)
    case Stop(reason) =>       shutdown(reason); stop
    case Exit(dead, reason) => handleTrapExit(dead, reason)
  }

  private[kernel] def handleTrapExit(dead: Actor, reason: Throwable): Unit = if (trapExit) {
    if (faultHandler.isDefined) {
      faultHandler.get match {
        case AllForOneStrategy(maxNrOfRetries, withinTimeRange) => restartLinkedActors(reason)
        case OneForOneStrategy(maxNrOfRetries, withinTimeRange) => dead.restart(reason)
      }
    }
  }

  private[this] def restartLinkedActors(reason: AnyRef) =
    linkedActors.toArray.toList.asInstanceOf[List[Actor]].foreach(_.restart(reason))

  private[Actor] def restart(reason: AnyRef) = synchronized {
    lifeCycleConfig match {
      case None => throw new IllegalStateException("Server [" + id + "] does not have a life-cycle defined.")
      case Some(LifeCycle(scope, shutdownTime)) => {
        scope match {
          case Permanent => {
            preRestart(reason, config)
            log.debug("Restarting actor [%s] configured as PERMANENT.", id)
            // FIXME SWAP actor
            postRestart(reason, config)
          }

          case Temporary =>
//            if (reason == 'normal) {
//              log.debug("Restarting actor [%s] configured as TEMPORARY (since exited naturally).", id)
//              scheduleRestart
//            } else log.info("Server [%s] configured as TEMPORARY will not be restarted (received unnatural exit message).", id)

          case Transient =>
            log.info("Server [%s] configured as TRANSIENT will not be restarted.", id)
        }
      }
    }
  }

  override def toString(): String = "Actor[" + id + "]"
}