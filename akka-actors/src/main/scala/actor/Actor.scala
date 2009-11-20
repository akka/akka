/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import java.net.InetSocketAddress
import java.util.HashSet

import se.scalablesolutions.akka.Config._
import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.stm.Transaction._
import se.scalablesolutions.akka.stm.TransactionManagement._
import se.scalablesolutions.akka.stm.{StmException, TransactionManagement}
import se.scalablesolutions.akka.nio.protobuf.RemoteProtocol.RemoteRequest
import se.scalablesolutions.akka.nio.{RemoteProtocolBuilder, RemoteClient, RemoteRequestIdFactory}
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util.Helpers.ReadWriteLock
import se.scalablesolutions.akka.util.Logging

import org.codehaus.aspectwerkz.proxy.Uuid

import org.multiverse.utils.ThreadLocalTransaction._

@serializable sealed abstract class LifeCycleMessage
case class Init(config: AnyRef) extends LifeCycleMessage
case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends LifeCycleMessage
case class Restart(reason: AnyRef) extends LifeCycleMessage
case class Exit(dead: Actor, killer: Throwable) extends LifeCycleMessage
case class Kill(killer: Actor) extends LifeCycleMessage
//case object TransactionalInit extends LifeCycleMessage

class ActorKilledException(val killed: Actor, val killer: Actor) extends RuntimeException("Actor [" + killed + "] killed by [" + killer + "]")

sealed abstract class DispatcherType
object DispatcherType {
  case object EventBasedThreadPooledProxyInvokingDispatcher extends DispatcherType
  case object EventBasedSingleThreadDispatcher extends DispatcherType
  case object EventBasedThreadPoolDispatcher extends DispatcherType
  case object ThreadBasedDispatcher extends DispatcherType
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActorMessageInvoker(val actor: Actor) extends MessageInvoker {
  def invoke(handle: MessageInvocation) = actor.invoke(handle)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor {
  val TIMEOUT = config.getInt("akka.actor.timeout", 5000)
  val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)

  def actor(body: PartialFunction[Any, Unit]): Actor = new Actor() {
    start
    def receive = body
  }

  def actor(lifeCycleConfig: LifeCycle)(body: PartialFunction[Any, Unit]): Actor = new Actor() {
    lifeCycle = Some(lifeCycleConfig)
    start
    def receive = body
  }
}

/**
 * Actor base trait that should be extended by or mixed to create an Actor with the semantics of the 'Actor Model':
 * <a href="http://en.wikipedia.org/wiki/Actor_model">http://en.wikipedia.org/wiki/Actor_model</a>
 * <p/>
 * An actor has a well-defined (non-cyclic) life-cycle.
 * <pre>
 * => NEW (newly created actor) - can't receive messages (yet)
 *     => STARTED (when 'start' is invoked) - can receive messages
 *         => SHUT DOWN (when 'exit' is invoked) - can't do anything
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Actor extends Logging with TransactionManagement {
  ActorRegistry.register(this)
  
  // FIXME http://www.assembla.com/spaces/akka/tickets/56-Change-UUID-generation-for-the-TransactionManagement-trait
  val uuid = Uuid.newUuid.toString

  // private fields
  @volatile private var _isRunning: Boolean = false
  @volatile private var _isShutDown: Boolean = false
  private var _hotswap: Option[PartialFunction[Any, Unit]] = None
  private var _config: Option[AnyRef] = None
  private val _remoteFlagLock = new ReadWriteLock 
  private[akka] var _remoteAddress: Option[InetSocketAddress] = None
  private[akka] val _linkedActors = new HashSet[Actor]
  private[akka] var _mailbox: MessageQueue = _
  private[akka] var _supervisor: Option[Actor] = None

  /**
   * This field should normally not be touched by user code, which should instead use the 'reply' method.
   * But it can be used for advanced use-cases when one might want to store away the future and
   * resolve it later and/or somewhere else.
   */
  protected var senderFuture: Option[CompletableFutureResult] = None

  // ====================================
  // ==== USER CALLBACKS TO OVERRIDE ====
  // ====================================

  /**
   *  User overridable callback/setting.
   *
   * Identifier for actor, does not have to be a unique one. Default is the class name.
   *
   * This field is used for logging etc. but also as the identifier for persistence, which means that you can
   * use a custom name to be able to retrieve the "correct" persisted state upon restart, remote restart etc.
   */
  protected[akka] var id: String = this.getClass.getName

  /**
   * User overridable callback/setting.
   *
   * Defines the default timeout for '!!' invocations, e.g. the timeout for the future returned by the call to '!!'.
   */
  @volatile var timeout: Long = Actor.TIMEOUT

  /**
   * User overridable callback/setting.
   *
   * User can (and is encouraged to) override the default configuration (newEventBasedThreadPoolDispatcher)
   * so it fits the specific use-case that the actor is used for.
   * <p/>
   * It is beneficial to have actors share the same dispatcher, easily +100 actors can share the same.
   * <p/>
   * But if you are running many many actors then it can be a good idea to have split them up in terms of
   * dispatcher sharing.
   * <p/>
   * Default is that all actors that are created and spawned from within this actor is sharing the same
   * dispatcher as its creator.
   * <pre>
   *   val dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher
   *   dispatcher
   *     .withNewThreadPoolWithBoundedBlockingQueue(100)
   *     .setCorePoolSize(16)
   *     .setMaxPoolSize(128)
   *     .setKeepAliveTimeInMillis(60000)
   *     .setRejectionPolicy(new CallerRunsPolicy)
   *     .buildThreadPool
   * </pre>
   */
  protected[akka] var messageDispatcher: MessageDispatcher = {
    val dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher(getClass.getName)
    _mailbox = dispatcher.messageQueue
    dispatcher
  }

  /**
   * User overridable callback/setting.
   *
   * Set trapExit to the list of exception classes that the actor should be able to trap
   * from the actor it is supervising. When the supervising actor throws these exceptions
   * then they will trigger a restart.
   * <p/>  
   * <pre>
   * // trap all exceptions
   * trapExit = List(classOf[Throwable])
   *
   * // trap specific exceptions only
   * trapExit = List(classOf[MyApplicationException], classOf[MyApplicationError])
   * </pre>
   */
  protected[this] var trapExit: List[Class[_ <: Throwable]] = Nil

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
   * Defines the life-cycle for a supervised actor. Default is 'LifeCycle(Permanent)' but can be overridden.
   */
  @volatile var lifeCycle: Option[LifeCycle] = Some(LifeCycle(Permanent))

  /**
   * User overridable callback/setting.
   *
   * Set to true if messages should have REQUIRES_NEW semantics, e.g. a new transaction should
   * start if there is no one running, else it joins the existing transaction.
   */
  @volatile protected var isTransactionRequiresNew = false

  /**
   * User overridable callback/setting.
   *
   * Partial function implementing the server logic.
   * To be implemented by subclassing server.
   * <p/>
   * Example code:
   * <pre>
   *   def receive = {
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
  protected def init(config: AnyRef) = {}

  /**
   * User overridable callback/setting.
   *
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def preRestart(reason: AnyRef, config: Option[AnyRef]) = {}

  /**
   * User overridable callback/setting.
   *
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def postRestart(reason: AnyRef, config: Option[AnyRef]) = {}

  /**
   * User overridable callback/setting.
   *
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  protected def initTransactionalState() = {}

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
    if (_isShutDown) throw new IllegalStateException("Can't restart an actor that have been shut down with 'exit'")
    if (!_isRunning) {
      dispatcher.registerHandler(this, new ActorMessageInvoker(this))
      messageDispatcher.start
      _isRunning = true
      //if (isTransactional) this !! TransactionalInit
    }
    log.info("[%s] has started", toString)
  }

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Delegates to 'stop'
   */
  protected def exit = stop

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop = synchronized {
    if (_isRunning) {
      messageDispatcher.unregisterHandler(this)
      if (messageDispatcher.isInstanceOf[ThreadBasedDispatcher]) messageDispatcher.shutdown
      // FIXME: Need to do reference count to know if EventBasedThreadPoolDispatcher and EventBasedSingleThreadDispatcher can be shut down
      _isRunning = false
      _isShutDown = true
      shutdown
    }
  }

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   */
  def !(message: AnyRef) =
    if (_isRunning) postMessageToMailbox(message)
    else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * <p/>
   * It waits on the reply either until it receives it (in the form of <code>Some(replyMessage)</code>)
   * or until the timeout expires (which will return None). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * If you are sending messages using <code>!!</code> then you <b>have to</b> use <code>reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !![T](message: AnyRef, timeout: Long): Option[T] = if (_isRunning) {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout)
    val isActiveObject = message.isInstanceOf[Invocation]
    if (isActiveObject && message.asInstanceOf[Invocation].isVoid) future.completeWithResult(None)
    try {
      future.await
    } catch {
      case e: FutureTimeoutException =>
        if (isActiveObject) throw e
        else None
    }
    getResultOrThrowException(future)
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  
  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * <p/>
   * It waits on the reply either until it receives it (in the form of <code>Some(replyMessage)</code>)
   * or until the timeout expires (which will return None). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * If you are sending messages using <code>!!</code> then you <b>have to</b> use <code>reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !![T](message: AnyRef): Option[T] = !![T](message, timeout)

  /**
   * Sends a message asynchronously, but waits on a future indefinitely. E.g. emulates a synchronous call.
   * <p/>
   * <b>NOTE:</b>
   * Should be used with care (almost never), since very dangerous (will block a thread indefinitely if no reply).
   */
  def !?[T](message: AnyRef): T = if (_isRunning) {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, 0)
    future.awaitBlocking
    getResultOrThrowException(future).get
  } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")

  /**
   * Use <code>reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * <b>NOTE:</b>
   * Does only work together with the actor <code>!!</code> method and/or active objects not annotated
   * with <code>@oneway</code>.
   */
  protected[this] def reply(message: AnyRef) = senderFuture match {
    case None => throw new IllegalStateException(
      "\n\tNo sender in scope, can't reply. " +
      "\n\tHave you used the '!' message send or the '@oneway' active object annotation? " +
      "\n\tIf so, switch to '!!' (or remove '@oneway') which passes on an implicit future that will be bound by the argument passed to 'reply'." )
    case Some(future) => future.completeWithResult(message)
  }

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher = synchronized { messageDispatcher }

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(dispatcher: MessageDispatcher): Unit = synchronized {
    if (!_isRunning) {
      messageDispatcher = dispatcher
      _mailbox = messageDispatcher.messageQueue
      messageDispatcher.registerHandler(this, new ActorMessageInvoker(this))
    } else throw new IllegalArgumentException("Can not swap dispatcher for " + toString + " after it has been started")
  }
  
  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit = _remoteFlagLock.withWriteLock {
    makeRemote(new InetSocketAddress(hostname, port))
  }

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit = _remoteFlagLock.withWriteLock {
    _remoteAddress = Some(address)
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
    if (_isRunning) throw new IllegalArgumentException("Can not make actor transaction required after it has been started")
    else isTransactionRequiresNew = true
  }

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def link(actor: Actor) = {
    if (_isRunning) {
      _linkedActors.add(actor)
      if (actor._supervisor.isDefined) throw new IllegalStateException("Actor can only have one supervisor [" + actor + "], e.g. link(actor) fails")
      actor._supervisor = Some(this)
      log.debug("Linking actor [%s] to actor [%s]", actor, this)
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def unlink(actor: Actor) = {
    if (_isRunning) {
      if (!_linkedActors.contains(actor)) throw new IllegalStateException("Actor [" + actor + "] is not a linked actor, can't unlink")
      _linkedActors.remove(actor)
      actor._supervisor = None
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
  protected[this] def startLinkRemote(actor: Actor, hostname: String, port: Int) = {
    actor.makeRemote(hostname, port)
    actor.start
    link(actor)
  }

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawn[T <: Actor](actorClass: Class[T]): T = {
    val actor = actorClass.newInstance.asInstanceOf[T]
    if (!dispatcher.isInstanceOf[ThreadBasedDispatcher]) {
      actor.dispatcher = dispatcher
      actor._mailbox = _mailbox
    }
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnRemote[T <: Actor](actorClass: Class[T], hostname: String, port: Int): T = {
    val actor = actorClass.newInstance.asInstanceOf[T]
    actor.makeRemote(hostname, port)
    if (!dispatcher.isInstanceOf[ThreadBasedDispatcher]) {
      actor.dispatcher = dispatcher
      actor._mailbox = _mailbox
    }
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnLink[T <: Actor](actorClass: Class[T]): T = {
    val actor = spawn[T](actorClass)
    link(actor)
    actor
  }

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnLinkRemote[T <: Actor](actorClass: Class[T], hostname: String, port: Int): T = {
    val actor = spawn[T](actorClass)
    actor.makeRemote(hostname, port)
    link(actor)
    actor
  }

  // ================================
  // ==== IMPLEMENTATION DETAILS ====
  // ================================

  private def postMessageToMailbox(message: AnyRef): Unit = _remoteFlagLock.withReadLock { // the price you pay for being able to make an actor remote at runtime
    if (_remoteAddress.isDefined) {
      val requestBuilder = RemoteRequest.newBuilder
        .setId(RemoteRequestIdFactory.nextId)
        .setTarget(this.getClass.getName)
        .setTimeout(timeout)
        .setIsActor(true)
        .setIsOneWay(true)
        .setIsEscaped(false)
      val id = registerSupervisorAsRemoteActor
      if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)
      RemoteProtocolBuilder.setMessage(message, requestBuilder)
      RemoteClient.clientFor(_remoteAddress.get).send(requestBuilder.build)
    } else {
      val handle = new MessageInvocation(this, message, None, currentTransaction.get)
      handle.send
    }
  }

  private def postMessageToMailboxAndCreateFutureResultWithTimeout(message: AnyRef, timeout: Long): CompletableFutureResult = _remoteFlagLock.withReadLock { // the price you pay for being able to make an actor remote at runtime
    if (_remoteAddress.isDefined) {
      val requestBuilder = RemoteRequest.newBuilder                                                                                                
        .setId(RemoteRequestIdFactory.nextId)
        .setTarget(this.getClass.getName)
        .setTimeout(timeout)
        .setIsActor(true)
        .setIsOneWay(false)
        .setIsEscaped(false)
      RemoteProtocolBuilder.setMessage(message, requestBuilder)
      val id = registerSupervisorAsRemoteActor
      if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)
      val future = RemoteClient.clientFor(_remoteAddress.get).send(requestBuilder.build)
      if (future.isDefined) future.get
      else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
    } else {
      val future = new DefaultCompletableFutureResult(timeout)
      val handle = new MessageInvocation(this, message, Some(future), currentTransaction.get)
      handle.send
      future
    }
  }

  /**
   * Callback for the dispatcher. E.g. single entry point to the user code and all protected[this] methods
   */
  private[akka] def invoke(messageHandle: MessageInvocation) = synchronized {
    if (TransactionManagement.isTransactionalityEnabled) transactionalDispatch(messageHandle)
    else dispatch(messageHandle)
  }

  private def dispatch[T](messageHandle: MessageInvocation) = {
    setTransaction(messageHandle.tx)

    val message = messageHandle.message //serializeMessage(messageHandle.message)
    val future = messageHandle.future
    try {
      senderFuture = future
      if (base.isDefinedAt(message)) base(message) // invoke user actor's receive partial function
      else throw new IllegalArgumentException("No handler matching message [" + message + "] in " + toString)
    } catch {
      case e =>
        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (_supervisor.isDefined) _supervisor.get ! Exit(this, e)
        if (future.isDefined) future.get.completeWithException(this, e)
        else e.printStackTrace
    } finally {
      clearTransaction
    }
  }

  private def transactionalDispatch[T](messageHandle: MessageInvocation) = {
    setTransaction(messageHandle.tx)
    
    val message = messageHandle.message //serializeMessage(messageHandle.message)
    val future = messageHandle.future

    def proceed = {
      try {
        incrementTransaction
        if (base.isDefinedAt(message)) base(message) // invoke user actor's receive partial function
        else throw new IllegalArgumentException("Actor " + toString + " could not process message [" + message + "] since no matching 'case' clause in its 'receive' method could be found")
      } finally {
        decrementTransaction
      }
    }
    
    try {
      senderFuture = future
      if (isTransactionRequiresNew && !isTransactionInScope) {
        if (senderFuture.isEmpty) throw new StmException(
          "\n\tCan't continue transaction in a one-way fire-forget message send" +
          "\n\tE.g. using Actor '!' method or Active Object 'void' method" +
          "\n\tPlease use the Actor '!!', '!?' methods or Active Object method with non-void return type")
        atomic {
          proceed
        }
      } else proceed
    } catch {
      case e =>
        e.printStackTrace

        if (future.isDefined) future.get.completeWithException(this, e)
        else e.printStackTrace

        clearTransaction // need to clear currentTransaction before call to supervisor

        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (_supervisor.isDefined) _supervisor.get ! Exit(this, e)
    } finally {
      clearTransaction
    }
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) throw future.exception.get._2
    else future.result.asInstanceOf[Option[T]]

  private def base: PartialFunction[Any, Unit] = lifeCycles orElse (_hotswap getOrElse receive)

  private val lifeCycles: PartialFunction[Any, Unit] = {
    case Init(config) =>       init(config)
    case HotSwap(code) =>      _hotswap = code
    case Restart(reason) =>    restart(reason)
    case Exit(dead, reason) => handleTrapExit(dead, reason)
    case Kill(killer) =>       throw new ActorKilledException(this, killer)
//    case TransactionalInit =>  initTransactionalState
  }

  private[this] def handleTrapExit(dead: Actor, reason: Throwable): Unit = {
    if (trapExit.exists(_.isAssignableFrom(reason.getClass))) {
      if (faultHandler.isDefined) {
        faultHandler.get match {
          // FIXME: implement support for maxNrOfRetries and withinTimeRange in RestartStrategy
          case AllForOneStrategy(maxNrOfRetries, withinTimeRange) => restartLinkedActors(reason)
          case OneForOneStrategy(maxNrOfRetries, withinTimeRange) => dead.restart(reason)
        }
      } else throw new IllegalStateException("No 'faultHandler' defined for actor with the 'trapExit' member field set to non-empty list of exception classes - can't proceed " + toString)
    } else {
      if (_supervisor.isDefined) _supervisor.get ! Exit(dead, reason) // if 'trapExit' is not defined then pass the Exit on
    }
  }

  private[this] def restartLinkedActors(reason: AnyRef) = {
    _linkedActors.toArray.toList.asInstanceOf[List[Actor]].foreach { actor =>
      actor.lifeCycle match {
        case None => throw new IllegalStateException("Actor [" + actor.id + "] does not have a life-cycle defined.")
        case Some(LifeCycle(scope, _)) => {
          scope match {
            case Permanent =>
              actor.restart(reason)
            case Temporary =>
              log.info("Actor [%s] configured as TEMPORARY will not be restarted.", actor.id)
              _linkedActors.remove(actor) // remove the temporary actor
          }
        }
      }
    }
  }

  private[Actor] def restart(reason: AnyRef) = synchronized {
    preRestart(reason, _config)
    log.info("Restarting actor [%s] configured as PERMANENT.", id)
    postRestart(reason, _config)
  }

  private[akka] def registerSupervisorAsRemoteActor: Option[String] = synchronized {
    if (_supervisor.isDefined) {
      RemoteClient.clientFor(_remoteAddress.get).registerSupervisorForActor(this)
      Some(_supervisor.get.uuid)
    } else None
  }


  private[akka] def swapDispatcher(disp: MessageDispatcher) = synchronized {
    messageDispatcher = disp
    _mailbox = messageDispatcher.messageQueue
    messageDispatcher.registerHandler(this, new ActorMessageInvoker(this))
  }

  private def serializeMessage(message: AnyRef): AnyRef = if (Actor.SERIALIZE_MESSAGES) {
    if (!message.isInstanceOf[String] &&
      !message.isInstanceOf[Byte] &&
      !message.isInstanceOf[Int] &&
      !message.isInstanceOf[Long] &&
      !message.isInstanceOf[Float] &&
      !message.isInstanceOf[Double] &&
      !message.isInstanceOf[Boolean] &&
      !message.isInstanceOf[Char] &&
      !message.isInstanceOf[Tuple2[_,_]] &&
      !message.isInstanceOf[Tuple3[_,_,_]] &&
      !message.isInstanceOf[Tuple4[_,_,_,_]] &&
      !message.isInstanceOf[Tuple5[_,_,_,_,_]] &&
      !message.isInstanceOf[Tuple6[_,_,_,_,_,_]] &&
      !message.isInstanceOf[Tuple7[_,_,_,_,_,_,_]] &&
      !message.isInstanceOf[Tuple8[_,_,_,_,_,_,_,_]] &&
      !message.getClass.isArray &&
      !message.isInstanceOf[List[_]] &&
      !message.isInstanceOf[scala.collection.immutable.Map[_,_]] &&
      !message.isInstanceOf[scala.collection.immutable.Set[_]] &&
      !message.isInstanceOf[scala.collection.immutable.Tree[_,_]] &&
      !message.getClass.isAnnotationPresent(Annotations.immutable)) {
      Serializer.Java.deepClone(message)
    } else message
  } else message

  override def toString(): String = "Actor[" + id + ":" + uuid + "]"
}
