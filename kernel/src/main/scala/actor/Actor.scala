/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.actor

import java.util.concurrent.CopyOnWriteArraySet

import kernel.reactor._
import kernel.config.ScalaConfig._
import kernel.nio.{RemoteClient, RemoteRequest}
import kernel.stm.{TransactionAwareWrapperException, TransactionManagement, Transaction}
import kernel.util.Logging
import org.codehaus.aspectwerkz.proxy.Uuid

sealed abstract class LifecycleMessage
case class Init(config: AnyRef) extends LifecycleMessage
case class Stop(reason: AnyRef) extends LifecycleMessage
case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends LifecycleMessage
case class Restart(reason: AnyRef) extends LifecycleMessage
case class Exit(dead: Actor, killer: Throwable) extends LifecycleMessage

sealed abstract class DispatcherType
object DispatcherType {
  case object EventBasedThreadPooledProxyInvokingDispatcher extends DispatcherType
  case object EventBasedSingleThreadDispatcher extends DispatcherType
  case object EventBasedThreadPoolDispatcher extends DispatcherType
  case object ThreadBasedDispatcher extends DispatcherType
}

class ActorMessageHandler(val actor: Actor) extends MessageHandler {
  def handle(handle: MessageHandle) = actor.handle(handle)
}

trait Actor extends Logging with TransactionManagement {  
  val id: String = this.getClass.toString
  val uuid = Uuid.newUuid.toString
  @volatile private[this] var isRunning: Boolean = false

  protected[Actor] var mailbox: MessageQueue = _
  protected[this] var senderFuture: Option[CompletableFutureResult] = None
  protected[this] val linkedActors = new CopyOnWriteArraySet[Actor]

  protected[kernel] var supervisor: Option[Actor] = None
  protected[actor] var lifeCycleConfig: Option[LifeCycle] = None

  private var hotswap: Option[PartialFunction[Any, Unit]] = None
  private var config: Option[AnyRef] = None

  // ====================================
  // ==== USER CALLBACKS TO OVERRIDE ====
  // ====================================

  /**
   * User overridable callback/setting.
   *
   * Defines the default timeout for '!!' invocations, e.g. the timeout for the future returned by the call to '!!'.
   */
  @volatile var timeout: Long = 5000L

  /**
   * User overridable callback/setting.
   *
   * Setting 'isRemote' to true means that an actor will be moved to and invoked on a remote host.
   * See also 'makeRemote'.
   */
  @volatile private[this] var isRemote = false

  /**
   * User overridable callback/setting.
   *
   * Setting 'isTransactional' to true means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking: 
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   * See also 'makeTransactional'.
   */
  @volatile private[this] var isTransactional = false

  /**
   * User overridable callback/setting.
   *
   * User can (and is encouraged to) override the default configuration so it fits the specific use-case that the actor is used for.
   * <p/>
   * It is beneficial to have actors share the same dispatcher, easily +100 actors can share the same.
   * <br/>
   * But if you are running many many actors then it can be a good idea to have split them up in terms of dispatcher sharing.
   * <br/>
   * Default is that all actors that are created and spawned from within this actor is sharing the same dispatcher as its creator.
   * <p/>
   * There are currently two different dispatchers available (but the interface can easily be implemented for custom implementation):
   * <pre/>
   *  // default - executorService can be build up using the ThreadPoolBuilder
   *  new EventBasedThreadPoolDispatcher(executor: ExecutorService)
   * 
   *  new EventBasedSingleThreadDispatcher
   * </pre>
   */
  protected[Actor] var dispatcher: MessageDispatcher = {
    val threadPool = ThreadPoolBuilder.newBuilder.newThreadPoolWithLinkedBlockingQueueWithCapacity(0).build
    val dispatcher = new EventBasedThreadPoolDispatcher(threadPool)
    mailbox = dispatcher.messageQueue
    dispatcher.registerHandler(this, new ActorMessageHandler(this))
    dispatcher
  }

  /**
   * User overridable callback/setting.
   *
   * Set trapExit to true if actor should be able to trap linked actors exit messages.
   */
  protected[this] var trapExit: Boolean = false

  /**
   * User overridable callback/setting.
   *
   * If 'trapExit' is set for the actor to act as supervisor, then a faultHandler must be defined.
   * Can be one of:
   * <pre/>
   *  AllForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int)
   * 
   *  OneForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int)
   * </pre>
   */
  protected var faultHandler: Option[FaultHandlingStrategy] = None

  /**
   * User overridable callback/setting.
   *
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
   * User overridable callback/setting.
   *
   * Optional callback method that is called during initialization.
   * To be implemented by subclassing actor.
   */
  protected def init(config: AnyRef) {}

  /**
   * User overridable callback/setting.
   *
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def preRestart(reason: AnyRef, config: Option[AnyRef]) {}

  /**
   * User overridable callback/setting.
   *
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {}

  /**
   * User overridable callback/setting.
   *
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  protected def shutdown(reason: AnyRef) {}

  // =============
  // ==== API ====
  // =============

  /**
   * Starts up the actor and its message queue.
   */
  def start = synchronized  {
    if (!isRunning) {
      dispatcher.start
      isRunning = true
    }
  }

  /**
   * Stops the actor and its message queue.
   */
  def stop = synchronized {
    if (isRunning) {
      dispatcher.unregisterHandler(this)
      isRunning = false
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }
  
  /**
   * TODO: document
   */
  def !(message: AnyRef): Unit = if (isRunning) {
    if (TransactionManagement.isTransactionalityEnabled) transactionalDispatch(message, timeout, false, true)
    else postMessageToMailbox(message)
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  
  /**
   * TODO: document
   */
  def !![T](message: AnyRef, timeout: Long): Option[T] = if (isRunning) {
    if (TransactionManagement.isTransactionalityEnabled) {
      transactionalDispatch(message, timeout, false, false)
    } else {
      val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout)
      future.await
      getResultOrThrowException(future)
    }
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  /**
   * TODO: document
   */
  def !![T](message: AnyRef): Option[T] = !![T](message, timeout)

  /**
   * TODO: document
   */
  def !?[T](message: AnyRef): T = if (isRunning) {
    if (TransactionManagement.isTransactionalityEnabled) {
      transactionalDispatch(message, 0, true, false).get
    } else {
      val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, 0)
      future.awaitBlocking
      getResultOrThrowException(future).get
    }
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  /**
   * TODO: document
   */
  protected[this] def reply(message: AnyRef) = senderFuture match {
    case None => throw new IllegalStateException("No sender in scope, can't reply. Have you used '!' (async, fire-and-forget)? If so, switch to '!!' which will return a future to wait on." )
    case Some(future) => future.completeWithResult(message)
  }

  // FIXME can be deadlock prone if cyclic linking? - HOWTO?
  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will receive a notification nif the linked actor has crashed.
   * If the 'trapExit' flag has been set then it will 'trap' the failure and automatically restart the linked actors according to the restart strategy defined by the 'faultHandler'.
   * <p/>
   * To be invoked from within the actor itself. 
   */
  protected[this] def link(actor: Actor) = synchronized { 
    if (isRunning) {
      linkedActors.add(actor)
      if (actor.supervisor.isDefined) throw new IllegalStateException("Actor can only have one supervisor [" + actor + "], e.g. link(actor) fails")
      actor.supervisor = Some(this)
      log.debug("Linking actor [%s] to actor [%s]", actor, this)
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

    // FIXME can be deadlock prone if cyclic linking? - HOWTO?
  /**
   * Unlink the actor. 
   * <p/>
   * To be invoked from within the actor itself. 
   */
  protected[this] def unlink(actor: Actor) = synchronized { 
    if (isRunning) {
      if (!linkedActors.contains(actor)) throw new IllegalStateException("Actor [" + actor + "] is not a linked actor, can't unlink")
      linkedActors.remove(actor)
      actor.supervisor = None
      log.debug("Unlinking actor [%s] from actor [%s]", actor, this)
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }
  
  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself. 
   */
  protected[this] def startLink(actor: Actor) = synchronized {
    actor.start
    link(actor)
  }

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself. 
   */
  protected[this] def startLinkRemote(actor: Actor) = synchronized {
    actor.makeRemote
    actor.start
    link(actor)
  }

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself. 
   */
  protected[this] def spawn(actorClass: Class[_]): Actor = synchronized {
    val actor = actorClass.newInstance.asInstanceOf[Actor]   
    actor.dispatcher = dispatcher
    actor.mailbox = mailbox
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself. 
   */
  protected[this] def spawnRemote(actorClass: Class[_]): Actor = synchronized {
    val actor = actorClass.newInstance.asInstanceOf[Actor]   
    actor.makeRemote
    actor.dispatcher = dispatcher
    actor.mailbox = mailbox
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself. 
   */
  protected[this] def spawnLink(actorClass: Class[_]): Actor = synchronized {
    val actor = spawn(actorClass)
    link(actor)
    actor
  }

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself. 
   */
  protected[this] def spawnLinkRemote(actorClass: Class[_]): Actor = synchronized {
    val actor = spawn(actorClass)
    actor.makeRemote
    link(actor)
    actor
  }

  /**
   * Invoking 'makeRemote' means that an actor will be moved to andinvoked on a remote host
   */
  def makeRemote = isRemote = true

  /**
   * Invoking 'makeTransactional' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking: 
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   */
  def makeTransactional = synchronized {
    if (isRunning) throw new IllegalArgumentException("Can not make actor transactional after it has been started") 
    else isTransactional = true
  }

  // ================================
  // ==== IMPLEMENTATION DETAILS ====
  // ================================

  private def postMessageToMailbox(message: AnyRef): Unit =
    if (isRemote) {
      val supervisorUuid = registerSupervisorAsRemoteActor
      RemoteClient.send(new RemoteRequest(true, message, null, this.getClass.getName, timeout, null, true, false, supervisorUuid))
    } else mailbox.append(new MessageHandle(this, message, None, activeTx))

  private def postMessageToMailboxAndCreateFutureResultWithTimeout(message: AnyRef, timeout: Long): CompletableFutureResult =
    if (isRemote) {
      val supervisorUuid = registerSupervisorAsRemoteActor
      val future = RemoteClient.send(new RemoteRequest(true, message, null, this.getClass.getName, timeout, null, false, false, supervisorUuid))  
      if (future.isDefined) future.get
      else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
    }
    else {
      val future = new DefaultCompletableFutureResult(timeout)
      mailbox.append(new MessageHandle(this, message, Some(future), TransactionManagement.threadBoundTx.get))
      future
    }
  
  private def transactionalDispatch[T](message: AnyRef, timeout: Long, blocking: Boolean, oneWay: Boolean): Option[T] = {
    tryToCommitTransaction
    if (isInExistingTransaction) joinExistingTransaction
    else if (isTransactional) startNewTransaction
    incrementTransaction
    try {
      if (oneWay) {
        postMessageToMailbox(message)
        None
      } else {
        val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout)
        if (blocking) future.awaitBlocking
        else future.await
        getResultOrThrowException(future)
      }
    } catch {
      case e: TransactionAwareWrapperException =>
        e.cause.printStackTrace
        rollback(e.tx)
        throw e.cause
    } finally {
      decrementTransaction
      if (isTransactionAborted) removeTransactionIfTopLevel
      else tryToPrecommitTransaction
      TransactionManagement.threadBoundTx.set(None)
    }
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      if (TransactionManagement.isTransactionalityEnabled) throw new TransactionAwareWrapperException(cause, activeTx)
      else throw cause
    } else {
      future.result.asInstanceOf[Option[T]]
    }

  private[kernel] def handle(messageHandle: MessageHandle) = synchronized {
    val message = messageHandle.message
    val future = messageHandle.future
    try {
      if (messageHandle.tx.isDefined) TransactionManagement.threadBoundTx.set(messageHandle.tx)
      senderFuture = future
      if (base.isDefinedAt(message)) base(message) // invoke user actor's receive partial function
      else throw new IllegalArgumentException("No handler matching message [" + message + "] in " + toString)
    } catch {
      case e =>
        // FIXME to fix supervisor restart of actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (supervisor.isDefined) supervisor.get ! Exit(this, e)
        if (future.isDefined) future.get.completeWithException(this, e)
        else e.printStackTrace
    } finally {
      TransactionManagement.threadBoundTx.set(None)
    }
  }

  private def base: PartialFunction[Any, Unit] = lifeCycle orElse (hotswap getOrElse receive)

  private val lifeCycle: PartialFunction[Any, Unit] = {
    case Init(config) =>       init(config)
    case HotSwap(code) =>      hotswap = code
    case Restart(reason) =>    restart(reason)
    case Stop(reason) =>       shutdown(reason); stop
    case Exit(dead, reason) => handleTrapExit(dead, reason)
  }

  private[kernel] def handleTrapExit(dead: Actor, reason: Throwable): Unit = {
    if (trapExit) {
      if (faultHandler.isDefined) {
        faultHandler.get match {
          case AllForOneStrategy(maxNrOfRetries, withinTimeRange) => restartLinkedActors(reason)
          case OneForOneStrategy(maxNrOfRetries, withinTimeRange) => dead.restart(reason)
        }
      } else throw new IllegalStateException("No 'faultHandler' defined for actor with the 'trapExit' flag set to true " + toString)
    } else {
      if (supervisor.isDefined) supervisor.get ! Exit(dead, reason) // if 'trapExit' is not defined then pass the Exit on
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
            log.info("Restarting actor [%s] configured as PERMANENT.", id)
            postRestart(reason, config)
          }

          case Temporary =>
          // FIXME handle temporary actors
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

  private[kernel] def registerSupervisorAsRemoteActor: Option[String] = {
    if (supervisor.isDefined) {
      RemoteClient.registerSupervisorForActor(this)
      Some(supervisor.get.uuid)
    } else None
  }

  override def toString(): String = "Actor[" + id + "]"
}
