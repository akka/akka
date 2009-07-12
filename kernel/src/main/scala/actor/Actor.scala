/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.actor

import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet

import kernel.nio.{RemoteServer, RemoteClient, RemoteRequest}
import kernel.reactor._
import kernel.config.ScalaConfig._
import kernel.stm.TransactionManagement
import kernel.util.Helpers.ReadWriteLock
import kernel.util.{Serializer, JSONSerializer, Logging}

sealed abstract class LifecycleMessage
case class Init(config: AnyRef) extends LifecycleMessage
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

class ActorMessageInvoker(val actor: Actor) extends MessageInvoker {
  def invoke(handle: MessageInvocation) = actor.invoke(handle)
}

object Actor {
  val TIMEOUT = kernel.Kernel.config.getInt("akka.actor.timeout", 5000)
  val SERIALIZE_MESSAGES = kernel.Kernel.config.getBool("akka.actor.serialize-messages", false)
}

trait Actor extends Logging with TransactionManagement {
  @volatile private[this] var isRunning: Boolean = false
  private[this] val remoteFlagLock = new ReadWriteLock
  private[this] val transactionalFlagLock = new ReadWriteLock

  private var hotswap: Option[PartialFunction[Any, Unit]] = None
  private var config: Option[AnyRef] = None
  @volatile protected[this] var isTransactional = false
  @volatile protected[this] var remoteAddress: Option[InetSocketAddress] = None
  @volatile protected[kernel] var supervisor: Option[Actor] = None
  protected[Actor] var mailbox: MessageQueue = _
  protected[this] var senderFuture: Option[CompletableFutureResult] = None
  protected[this] val linkedActors = new CopyOnWriteArraySet[Actor]
  protected[actor] var lifeCycleConfig: Option[LifeCycle] = None

  // FIXME switch to JSON serialization
  protected[this] val serializer: Serializer = JSONSerializer

  // ====================================
  // ==== USER CALLBACKS TO OVERRIDE ====
  // ====================================

  /**
   * User overridable callback/setting.
   *
   * Defines the default timeout for '!!' invocations, e.g. the timeout for the future returned by the call to '!!'.
   */
  @volatile var timeout: Long = Actor.TIMEOUT

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
   * <pre>
   *   dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher
   *     .withNewThreadPoolWithBoundedBlockingQueue(100)
   *     .setCorePoolSize(16)
   *     .setMaxPoolSize(128)
   *     .setKeepAliveTimeInMillis(60000)
   *     .setRejectionPolicy(new CallerRunsPolicy)
   *     .buildThreadPool
   * </pre>
   */
  protected[kernel] var dispatcher: MessageDispatcher = {
    val dispatcher = new EventBasedThreadPoolDispatcher
    mailbox = dispatcher.messageQueue
    dispatcher.registerHandler(this, new ActorMessageInvoker(this))
    dispatcher
  }

  /**
   * User overridable callback/setting.
   *
   * Identifier for actor, does not have to be a unique one. Simply the one used in logging etc.
   */
  protected[this] var id: String = this.getClass.toString

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
  protected def shutdown {}

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
      shutdown
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   */
  def !(message: AnyRef): Unit =
    if (isRunning) postMessageToMailbox(message)
    else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * It waits on the reply either until it receives it (returns Some(replyMessage) or until the timeout expires (returns None).
   * E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * If you are sending messages using '!!' then you *have to* use reply(..) sending a reply message to the original sender.
   * If not then the sender will unessecary block until the timeout expires.
   */
  def !![T](message: AnyRef, timeout: Long): Option[T] = if (isRunning) {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout)
    future.await
    getResultOrThrowException(future)
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * It waits on the reply either until it receives it (returns Some(replyMessage) or until the actor default timeout expires (returns None).
   * E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * If you are sending messages using '!!' then you *have to* use reply(..) sending a reply message to the original sender.
   * If not then the sender will unessecary block until the timeout expires.
   */
  def !![T](message: AnyRef): Option[T] = !![T](message, timeout)

  /**
   * Sends a message asynchronously, but waits on a future indefinitely. E.g. emulates a synchronous call.
   * E.g. send-and-receive-eventually semantics.
   */
  def !?[T](message: AnyRef): T = if (isRunning) {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, 0)
    future.awaitBlocking
    getResultOrThrowException(future).get
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  /**
   * Use reply(..) to reply with a message to the original sender of the message currently being processed.
   */
  protected[this] def reply(message: AnyRef) = senderFuture match {
    case None => throw new IllegalStateException("No sender in scope, can't reply. Have you used '!' (async, fire-and-forget)? If so, switch to '!!' which will return a future to wait on." )
    case Some(future) => future.completeWithResult(message)
  }

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def setDispatcher(disp: MessageDispatcher) = synchronized {
    if (!isRunning) dispatcher = disp
    else throw new IllegalArgumentException("Can not swap dispatcher for " + toString + " after it has been started")
  }
  
  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit = remoteFlagLock.withWriteLock {
    makeRemote(new InetSocketAddress(hostname, port))
  }

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit = remoteFlagLock.withWriteLock {
    remoteAddress = Some(address)
  }

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking:
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   */
  def makeTransactionRequired = synchronized {
    if (isRunning) throw new IllegalArgumentException("Can not make actor transaction required after it has been started")
    else isTransactional = true
  }

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will receive a notification nif the linked actor has crashed.
   * If the 'trapExit' flag has been set then it will 'trap' the failure and automatically restart the linked actors according to the restart strategy defined by the 'faultHandler'.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def link(actor: Actor) = {
    if (isRunning) {
      linkedActors.add(actor)
      if (actor.supervisor.isDefined) throw new IllegalStateException("Actor can only have one supervisor [" + actor + "], e.g. link(actor) fails")
      actor.supervisor = Some(this)
      log.debug("Linking actor [%s] to actor [%s]", actor, this)
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def unlink(actor: Actor) = {
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
  protected[this] def startLink(actor: Actor) = {
    actor.start
    link(actor)
  }

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def startLinkRemote(actor: Actor) = {
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    actor.start
    link(actor)
  }

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawn(actorClass: Class[_]): Actor = {
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
  protected[this] def spawnRemote(actorClass: Class[_]): Actor = {
    val actor = actorClass.newInstance.asInstanceOf[Actor]
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
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
  protected[this] def spawnLink(actorClass: Class[_]): Actor = {
    val actor = spawn(actorClass)
    link(actor)
    actor
  }

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnLinkRemote(actorClass: Class[_]): Actor = {
    val actor = spawn(actorClass)
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    link(actor)
    actor
  }

  // ================================
  // ==== IMPLEMENTATION DETAILS ====
  // ================================

  private def postMessageToMailbox(message: AnyRef): Unit = remoteFlagLock.withReadLock { // the price you pay for being able to make an actor remote at runtime
    if (remoteAddress.isDefined) {
      val supervisorUuid = registerSupervisorAsRemoteActor
      RemoteClient.clientFor(remoteAddress.get).send(new RemoteRequest(message, null, this.getClass.getName, timeout, supervisorUuid, true, true, false))
    } else {
      val handle = new MessageInvocation(this, message, None, TransactionManagement.threadBoundTx.get)
      mailbox.append(handle)
      latestMessage = Some(handle)
    }
  }

  private def postMessageToMailboxAndCreateFutureResultWithTimeout(message: AnyRef, timeout: Long): CompletableFutureResult = remoteFlagLock.withReadLock { // the price you pay for being able to make an actor remote at runtime
    if (remoteAddress.isDefined) {
      val supervisorUuid = registerSupervisorAsRemoteActor
      val future = RemoteClient.clientFor(remoteAddress.get).send(new RemoteRequest(message, null, this.getClass.getName, timeout, supervisorUuid, true, false, false))
      if (future.isDefined) future.get
      else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
    } else {
      val future = new DefaultCompletableFutureResult(timeout)
      val handle = new MessageInvocation(this, message, Some(future), TransactionManagement.threadBoundTx.get)
      mailbox.append(handle)
      latestMessage = Some(handle)
      future
    }
  }

  /**
   * Callback for the dispatcher. E.g. single entry point to the user code and all protected[this] methods
   */
  private[kernel] def invoke(messageHandle: MessageInvocation) = synchronized {
    if (TransactionManagement.isTransactionalityEnabled) transactionalDispatch(messageHandle)
    else dispatch(messageHandle)
  }

  private def dispatch[T](messageHandle: MessageInvocation) = {
    if (messageHandle.tx.isDefined) TransactionManagement.threadBoundTx.set(messageHandle.tx)
    val message = messageHandle.message//serializeMessage(messageHandle.message)
    val future = messageHandle.future
    try {
      senderFuture = future
      if (base.isDefinedAt(message)) base(message) // invoke user actor's receive partial function
      else throw new IllegalArgumentException("No handler matching message [" + message + "] in " + toString)
    } catch {
      case e =>
        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (supervisor.isDefined) supervisor.get ! Exit(this, e)
        if (future.isDefined) future.get.completeWithException(this, e)
        else e.printStackTrace
    } finally {
      TransactionManagement.threadBoundTx.set(None)
    }
  }

  private def transactionalDispatch[T](messageHandle: MessageInvocation) = {
    if (messageHandle.tx.isDefined) TransactionManagement.threadBoundTx.set(messageHandle.tx)
    val message = messageHandle.message//serializeMessage(messageHandle.message)
    val future = messageHandle.future
    try {
      if (!tryToCommitTransaction && isTransactionTopLevel) handleCollision

      if (isInExistingTransaction) joinExistingTransaction
      else if (isTransactional) startNewTransaction

      incrementTransaction
      senderFuture = future
      if (base.isDefinedAt(message)) base(message) // invoke user actor's receive partial function
      else throw new IllegalArgumentException("No handler matching message [" + message + "] in " + toString)
    } catch {
      case e =>
        rollback(activeTx)
        TransactionManagement.threadBoundTx.set(None) // need to clear threadBoundTx before call to supervisor 
        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (supervisor.isDefined) supervisor.get ! Exit(this, e)
        if (future.isDefined) future.get.completeWithException(this, e)
        else e.printStackTrace
    } finally {
      decrementTransaction
      if (isTransactionAborted) removeTransactionIfTopLevel
      else tryToPrecommitTransaction
      rescheduleClashedMessages
      TransactionManagement.threadBoundTx.set(None)
    }
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      throw cause
    } else future.result.asInstanceOf[Option[T]]

  private def rescheduleClashedMessages = if (messageToReschedule.isDefined) {
    val handle = messageToReschedule.get
    val newTx = startNewTransaction
    val clone = new MessageInvocation(handle.sender, handle.message, handle.future, newTx)
    log.debug("Rescheduling message %s", clone)
    mailbox.append(clone) // FIXME append or prepend rescheduled messages?
  }

  private def base: PartialFunction[Any, Unit] = lifeCycle orElse (hotswap getOrElse receive)

  private val lifeCycle: PartialFunction[Any, Unit] = {
    case Init(config) =>       init(config)
    case HotSwap(code) =>      hotswap = code
    case Restart(reason) =>    restart(reason)
    case Exit(dead, reason) => handleTrapExit(dead, reason)
  }

  private[this] def handleTrapExit(dead: Actor, reason: Throwable): Unit = {
    if (trapExit) {
      if (faultHandler.isDefined) {
        faultHandler.get match {
          // FIXME: implement support for maxNrOfRetries and withinTimeRange in RestartStrategy
          case AllForOneStrategy(maxNrOfRetries, withinTimeRange) => restartLinkedActors(reason)
          case OneForOneStrategy(maxNrOfRetries, withinTimeRange) => dead.restart(reason)
        }
      } else throw new IllegalStateException("No 'faultHandler' defined for actor with the 'trapExit' flag set to true - can't proceed " + toString)
    } else {
      if (supervisor.isDefined) supervisor.get ! Exit(dead, reason) // if 'trapExit' is not defined then pass the Exit on
    }
  }

  private[this] def restartLinkedActors(reason: AnyRef) =
    linkedActors.toArray.toList.asInstanceOf[List[Actor]].foreach(_.restart(reason))

  private[Actor] def restart(reason: AnyRef) = synchronized {
    lifeCycleConfig match {
      case None => throw new IllegalStateException("Server [" + id + "] does not have a life-cycle defined.")

      // FIXME implement support for shutdown time
      case Some(LifeCycle(scope, shutdownTime, _)) => {
        scope match {
          case Permanent => {
            preRestart(reason, config)
            log.info("Restarting actor [%s] configured as PERMANENT.", id)
            postRestart(reason, config)
          }

          case Temporary =>
          // FIXME handle temporary actors correctly - restart if exited normally
//            if (reason == 'normal) {
//              log.debug("Restarting actor [%s] configured as TEMPORARY (since exited naturally).", id)
//              scheduleRestart
//            } else
            log.info("Server [%s] configured as TEMPORARY will not be restarted (received unnatural exit message).", id)

          case Transient =>
            log.info("Server [%s] configured as TRANSIENT will not be restarted.", id)
        }
      }
    }
  }

  private[kernel] def registerSupervisorAsRemoteActor: Option[String] = synchronized {
    if (supervisor.isDefined) {
      RemoteClient.clientFor(remoteAddress.get).registerSupervisorForActor(this)
      Some(supervisor.get.uuid)
    } else None
  }


  private[kernel] def swapDispatcher(disp: MessageDispatcher) = {
    dispatcher = disp
    mailbox = dispatcher.messageQueue
    dispatcher.registerHandler(this, new ActorMessageInvoker(this))
  }

  /*
  private def serializeMessage(message: AnyRef): AnyRef = if (Actor.SERIALIZE_MESSAGES) {
    if (!message.isInstanceOf[String] &&
      !message.isInstanceOf[Int] &&
      !message.isInstanceOf[Long] &&
      !message.isInstanceOf[Float] &&
      !message.isInstanceOf[Double] &&
      !message.isInstanceOf[Boolean] &&
      !message.isInstanceOf[Char] &&
      !message.isInstanceOf[java.lang.Integer] &&
      !message.isInstanceOf[java.lang.Long] &&
      !message.isInstanceOf[java.lang.Float] &&
      !message.isInstanceOf[java.lang.Double] &&
      !message.isInstanceOf[java.lang.Boolean] &&
      !message.isInstanceOf[java.lang.Character] &&
      !message.getClass.isAnnotationPresent(Annotations.immutable)) {
      serializer.deepClone(message)
    } else message
  } else message
    */
  override def toString(): String = "Actor[" + uuid + ":" + id + "]"
}
