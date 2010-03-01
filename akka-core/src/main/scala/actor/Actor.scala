/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.Config._
import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.stm.Transaction._
import se.scalablesolutions.akka.stm.TransactionManagement._
import se.scalablesolutions.akka.stm.{StmException, TransactionManagement}
import se.scalablesolutions.akka.remote.protobuf.RemoteProtocol.RemoteRequest
import se.scalablesolutions.akka.remote.{RemoteProtocolBuilder, RemoteClient, RemoteRequestIdFactory}
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util.{HashCode, Logging, UUID}

import org.multiverse.api.ThreadLocalTransaction._

import java.util.{Queue, HashSet}
import java.util.concurrent.ConcurrentLinkedQueue
import java.net.InetSocketAddress

/**
 * Implements the Transactor abstraction. E.g. a transactional actor.
 * <p/>
 * Equivalent to invoking the <code>makeTransactionRequired</code> method in the body of the <code>Actor</code
 */
trait Transactor extends Actor {
  makeTransactionRequired
}

/**
 * Extend this abstract class to create a remote actor.
 * <p/>
 * Equivalent to invoking the <code>makeRemote(..)</code> method in the body of the <code>Actor</code
 */
abstract class RemoteActor(hostname: String, port: Int) extends Actor {
  makeRemote(hostname, port)
}

@serializable sealed trait LifeCycleMessage
case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends LifeCycleMessage
case class Restart(reason: Throwable) extends LifeCycleMessage
case class Exit(dead: Actor, killer: Throwable) extends LifeCycleMessage
case object Kill extends LifeCycleMessage

class ActorKilledException private[akka](message: String) extends RuntimeException(message)

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
object Actor extends Logging {
  val TIMEOUT = config.getInt("akka.actor.timeout", 5000)
  val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)
  val HOSTNAME = config.getString("akka.remote.server.hostname", "localhost")
  val PORT = config.getInt("akka.remote.server.port", 9999)

  object Sender{
    implicit val Self: Option[Actor] = None
  }

  /**
   * Use to create an anonymous event-driven actor.
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = actor  {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def actor(body: PartialFunction[Any, Unit]): Actor = new Actor() {
    start
    def receive: PartialFunction[Any, Unit] = body
  }

  /**
   * Use to create an anonymous event-driven actor with both an init block and a message loop block.
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = actor  {
   *   ... // init stuff
   * } receive  {
   *   case msg => ... // handle message
   * }
   * </pre>
   *
   */
  def actor(body: => Unit) = {
    def handler(body: => Unit) = new {
      def receive(handler: PartialFunction[Any, Unit]) = new Actor() {
        start
        body
        def receive = handler
      }
    }
    handler(body)
  }

  /**
   * Use to create an anonymous event-driven actor with a body but no message loop block.
   * <p/>
   * This actor can <b>not</b> respond to any messages but can be used as a simple way to
   * spawn a lightweight thread to process some task.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * spawn  {
   *   ... // do stuff
   * }
   * </pre>
   */
  def spawn(body: => Unit): Actor = {
    case object Spawn
    new Actor() {
      start
      send(Spawn)
      def receive = {
        case Spawn => body; stop
      }
    }
  }

  /**
   * Use to create an anonymous event-driven actor with a life-cycle configuration.
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = actor(LifeCycle(Temporary))  {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def actor(lifeCycleConfig: LifeCycle)(body: PartialFunction[Any, Unit]): Actor = new Actor() {
    lifeCycle = Some(lifeCycleConfig)
    start
    def receive = body
  }

  /**
   * Use to create an anonymous event-driven remote actor.
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = actor("localhost", 9999)  {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def actor(hostname: String, port: Int)(body: PartialFunction[Any, Unit]): Actor = new Actor() {
    makeRemote(hostname, port)
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
trait Actor extends TransactionManagement {
  implicit protected val self: Option[Actor] = Some(this)
  implicit protected val transactionFamily: String = this.getClass.getName

  // Only mutable for RemoteServer in order to maintain identity across nodes
  private[akka] var _uuid = UUID.newUuid.toString

  // ====================================
  // private fields
  // ====================================

  @volatile private[this] var _isRunning = false
  @volatile private[this] var _isSuspended = true
  @volatile private[this] var _isShutDown = false
  @volatile private[this] var _isEventBased: Boolean = false
  @volatile private[akka] var _isKilled = false
  private var _hotswap: Option[PartialFunction[Any, Unit]] = None
  private[akka] var _remoteAddress: Option[InetSocketAddress] = None
  private[akka] var _linkedActors: Option[HashSet[Actor]] = None
  private[akka] var _supervisor: Option[Actor] = None
  private[akka] var _replyToAddress: Option[InetSocketAddress] = None
  private[akka] val _mailbox: Queue[MessageInvocation] = new ConcurrentLinkedQueue[MessageInvocation]

  // ====================================
  // protected fields
  // ====================================

  /**
   * The 'sender' field holds the sender of the message currently being processed.
   * <p/>
   * If the sender was an actor then it is defined as 'Some(senderActor)' and
   * if the sender was of some other instance then it is defined as 'None'.
   * <p/>
   * This sender reference can be used together with the '!' method for request/reply
   * message exchanges and which is in many ways better than using the '!!' method
   * which will make the sender wait for a reply using a *blocking* future.
   */
  protected var sender: Option[Actor] = None

  /**
   * The 'senderFuture' field should normally not be touched by user code, which should instead use the 'reply' method.
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
   * Identifier for actor, does not have to be a unique one.
   * Default is the class name.
   *
   * This field is used for logging, AspectRegistry.actorsFor, identifier for remote actor in RemoteServer etc.
   * But also as the identifier for persistence, which means that you can
   * use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  protected var id: String = this.getClass.getName

  /**
   * User overridable callback/setting.
   *
   * Defines the default timeout for '!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!'.
   */
  @volatile var timeout: Long = Actor.TIMEOUT

  /**
   * User overridable callback/setting.
   * <p/>
   * The default dispatcher is the <tt>Dispatchers.globalExecutorBasedEventDrivenDispatcher</tt>.
   * This means that all actors will share the same event-driven executor based dispatcher.
   * <p/>
   * You can override it so it fits the specific use-case that the actor is used for.
   * See the <tt>se.scalablesolutions.akka.dispatch.Dispatchers</tt> class for the different
   * dispatchers available.
   * <p/>
   * The default is also that all actors that are created and spawned from within this actor
   * is sharing the same dispatcher as its creator.
   */
  protected[akka] var messageDispatcher: MessageDispatcher = {
    val dispatcher = Dispatchers.globalExecutorBasedEventDrivenDispatcher
    _isEventBased = dispatcher.isInstanceOf[ExecutorBasedEventDrivenDispatcher]
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
  protected var trapExit: List[Class[_ <: Throwable]] = Nil

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
   * Defines the life-cycle for a supervised actor.
   */
  @volatile var lifeCycle: Option[LifeCycle] = None

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
   *   def receive =  {
   *     case Ping =>
   *       println("got a ping")
   *       reply("pong")
   *
   *     case OneWay =>
   *       println("got a oneway")
   *
   *     case _ =>
   *       println("unknown message, ignoring")
   * }
   * </pre>
   */
  protected def receive: PartialFunction[Any, Unit]

  /**
   * User overridable callback/setting.
   *
   * Optional callback method that is called during initialization.
   * To be implemented by subclassing actor.
   */
  protected def init {}

  /**
   * User overridable callback/setting.
   *
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def preRestart(reason: Throwable) {}

  /**
   * User overridable callback/setting.
   *
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  protected def postRestart(reason: Throwable) {}

  /**
   * User overridable callback/setting.
   *
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  protected def initTransactionalState {}

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
  def start: Actor = synchronized {
    if (_isShutDown) throw new IllegalStateException("Can't restart an actor that has been shut down with 'exit'")
    if (!_isRunning) {
      if (messageDispatcher.isShutdown && 
          messageDispatcher.isInstanceOf[Dispatchers.globalExecutorBasedEventDrivenDispatcher.type]) {
        messageDispatcher.asInstanceOf[ExecutorBasedEventDrivenDispatcher].init
      }
      messageDispatcher.register(this)
      messageDispatcher.start
      _isRunning = true
      init 
    }
    Actor.log.debug("[%s] has started", toString)
    ActorRegistry.register(this)
    this
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
      messageDispatcher.unregister(this)
      if (messageDispatcher.canBeShutDown) messageDispatcher.shutdown // shut down in the dispatcher's references is zero
      _isRunning = false
      _isShutDown = true
      shutdown
      ActorRegistry.unregister(this)
      _remoteAddress.foreach(address => RemoteClient.unregister(address.getHostName, address.getPort, uuid))
    }
  }

  def isRunning = _isRunning

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   *
   * If invoked from within an actor then the actor reference is implicitly passed on as the implicit 'sender' argument.
   * <p/>
   *
   * This actor 'sender' reference is then available in the receiving actor in the 'sender' member variable.
   * <pre>
   *   actor ! message
   * </pre>
   * <p/>
   *
   * If invoked from within a *non* Actor instance then either add this import to resolve the implicit argument:
   * <pre>
   *   import Actor.Sender.Self
   *   actor ! message
   * </pre>
   *
   * Or pass in the implicit argument explicitly:
   * <pre>
   *   actor.!(message)(Some(this))
   * </pre>
   *
   * Or use the 'send(..)' method;
   * <pre>
   *   actor.send(message)
   * </pre>
   */
  def !(message: Any)(implicit sender: Option[Actor]) = {
    //FIXME 2.8   def !(message: Any)(implicit sender: Option[Actor] = None) = {
    if (_isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (_isRunning) postMessageToMailbox(message, sender)
    else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Same as the '!' method but does not take an implicit sender as second parameter.
   */
  def send(message: Any) = {
    if (_isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (_isRunning) postMessageToMailbox(message, None)
    else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * <p/>
   * It waits on the reply either until it receives it (in the form of <code>Some(replyMessage)</code>)
   * or until the timeout expires (which will return None). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use '!' together with the 'sender' member field to
   * implement request/response message exchanges.
   * If you are sending messages using <code>!!</code> then you <b>have to</b> use <code>reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !![T](message: Any, timeout: Long): Option[T] = {
    if (_isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (_isRunning) {
      val from = if (sender != null && sender.isInstanceOf[Actor]) Some(sender.asInstanceOf[Actor])
      else None
      val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, None)
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
    } else throw new IllegalStateException(
      "Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * <p/>
   * It waits on the reply either until it receives it (in the form of <code>Some(replyMessage)</code>)
   * or until the timeout expires (which will return None). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use '!' together with the 'sender' member field to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>!!</code> then you <b>have to</b> use <code>reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !![T](message: Any): Option[T] = !![T](message, timeout)

  def !!!(message: Any): FutureResult = {
    if (_isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (_isRunning) {
      postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, None)
    } else throw new IllegalStateException(
      "Actor has not been started, you need to invoke 'actor.start' before using it")
  }
  
  /**
   * This method is evil and has been removed. Use '!!' with a timeout instead.
   */
  def !?[T](message: Any): T = throw new UnsupportedOperationException(
    "'!?' is evil and has been removed. Use '!!' with a timeout instead")

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with both '!' and '!!'. 
   */
  def forward(message: Any)(implicit sender: Option[Actor]) = {
    if (_isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (_isRunning) {
      val forwarder = sender.getOrElse(throw new IllegalStateException("Can't forward message when the forwarder/mediator is not an actor"))
      if (forwarder.getSenderFuture.isDefined) postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, forwarder.getSenderFuture)
      else if (forwarder.getSender.isDefined) postMessageToMailbox(message, forwarder.getSender)
      else throw new IllegalStateException("Can't forward message when initial sender is not an actor")
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Use <code>reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   */
  protected[this] def reply(message: Any) = {
    sender match {
      case Some(senderActor) =>
        senderActor ! message
      case None =>
        senderFuture match {
          case None =>
            throw new IllegalStateException(
              "\n\tNo sender in scope, can't reply. " +
              "\n\tYou have probably used the '!' method to either; " +
              "\n\t\t1. Send a message to a remote actor which does not have a contact address." +
              "\n\t\t2. Send a message from an instance that is *not* an actor" +
              "\n\t\t3. Send a message to an Active Object annotated with the '@oneway' annotation? " +
              "\n\tIf so, switch to '!!' (or remove '@oneway') which passes on an implicit future" +
              "\n\tthat will be bound by the argument passed to 'reply'. Alternatively, you can use setReplyToAddress to make sure the actor can be contacted over the network.")
          case Some(future) =>
            future.completeWithResult(message)
        }
    }
  }

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher = messageDispatcher

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit = synchronized {
    if (!_isRunning) {
      messageDispatcher.unregister(this)
      messageDispatcher = md
      messageDispatcher.register(this)
      _isEventBased = messageDispatcher.isInstanceOf[ExecutorBasedEventDrivenDispatcher]
    } else throw new IllegalArgumentException(
      "Can not swap dispatcher for " + toString + " after it has been started")
  }

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit =
    if (_isRunning) throw new IllegalStateException("Can't make a running actor remote. Make sure you call 'makeRemote' before 'start'.")
    else makeRemote(new InetSocketAddress(hostname, port))

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit =
    if (_isRunning) throw new IllegalStateException("Can't make a running actor remote. Make sure you call 'makeRemote' before 'start'.")
    else {
      _remoteAddress = Some(address)
      RemoteClient.register(address.getHostName, address.getPort, uuid)
      if (_replyToAddress.isEmpty) setReplyToAddress(Actor.HOSTNAME, Actor.PORT)
    }


  /**
   * Set the contact address for this actor. This is used for replying to messages sent asynchronously when no reply channel exists.
   */
  def setReplyToAddress(hostname: String, port: Int): Unit = setReplyToAddress(new InetSocketAddress(hostname, port))

  def setReplyToAddress(address: InetSocketAddress): Unit = _replyToAddress = Some(address)

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking:
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   */
  def makeTransactionRequired = synchronized {
    if (_isRunning) throw new IllegalArgumentException(
      "Can not make actor transaction required after it has been started")
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
    if (actor._supervisor.isDefined) throw new IllegalStateException(
      "Actor can only have one supervisor [" + actor + "], e.g. link(actor) fails")
    getLinkedActors.add(actor)
    actor._supervisor = Some(this)
    Actor.log.debug("Linking actor [%s] to actor [%s]", actor, this)
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def unlink(actor: Actor) = {
    if (!getLinkedActors.contains(actor)) throw new IllegalStateException(
      "Actor [" + actor + "] is not a linked actor, can't unlink")
    getLinkedActors.remove(actor)
    actor._supervisor = None
    Actor.log.debug("Unlinking actor [%s] from actor [%s]", actor, this)
  }

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def startLink(actor: Actor) = {
    try {
      actor.start
    } finally {
      link(actor)
    }
  }

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def startLinkRemote(actor: Actor, hostname: String, port: Int) = {
    try {
      actor.makeRemote(hostname, port)
      actor.start
    } finally {
      link(actor)
    }
  }

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawn[T <: Actor](actorClass: Class[T]): T = {
    val actor = spawnButDoNotStart(actorClass)
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnRemote[T <: Actor](actorClass: Class[T], hostname: String, port: Int): T = {
    val actor = spawnButDoNotStart(actorClass)
    actor.makeRemote(hostname, port)
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnLink[T <: Actor](actorClass: Class[T]): T = {
    val actor = spawnButDoNotStart(actorClass)
    try {
      actor.start
    } finally {
      link(actor)
    }
    actor
  }

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnLinkRemote[T <: Actor](actorClass: Class[T], hostname: String, port: Int): T = {
    val actor = spawnButDoNotStart(actorClass)
    try {
      actor.makeRemote(hostname, port)
      actor.start
    } finally {
      link(actor)
    }
    actor
  }

  /**
   * Returns the id for the actor.
   */
  def getId = id

  /**
   * Returns the uuid for the actor.
   */
  def uuid = _uuid

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] def _suspend = _isSuspended = true

  private[akka] def _resume = _isSuspended = false

  private[akka] def getSender = sender

  private[akka] def getSenderFuture = senderFuture

  private def spawnButDoNotStart[T <: Actor](actorClass: Class[T]): T = {
    val actor = actorClass.newInstance.asInstanceOf[T]
    if (!dispatcher.isInstanceOf[ThreadBasedDispatcher]) {
      actor.dispatcher = dispatcher
    }
    actor
  }

  protected[akka] def postMessageToMailbox(message: Any, sender: Option[Actor]): Unit = {
    if (_remoteAddress.isDefined) {
      val requestBuilder = RemoteRequest.newBuilder
          .setId(RemoteRequestIdFactory.nextId)
          .setTarget(this.getClass.getName)
          .setTimeout(this.timeout)
          .setUuid(this.id)
          .setIsActor(true)
          .setIsOneWay(true)
          .setIsEscaped(false)
      
      val id = registerSupervisorAsRemoteActor
      if(id.isDefined)
        requestBuilder.setSupervisorUuid(id.get)

      // set the source fields used to reply back to the original sender
      // (i.e. not the remote proxy actor)
      if(sender.isDefined) {
        val s = sender.get
        requestBuilder.setSourceTarget(s.getClass.getName)
        requestBuilder.setSourceUuid(s.uuid)

        val (host,port) = s._replyToAddress.map(a => (a.getHostName,a.getPort)).getOrElse((Actor.HOSTNAME,Actor.PORT))
        
        log.debug("Setting sending actor as %s @ %s:%s", s.getClass.getName, host, port)

        requestBuilder.setSourceHostname(host)
        requestBuilder.setSourcePort(port)
      }
      RemoteProtocolBuilder.setMessage(message, requestBuilder)
      RemoteClient.clientFor(_remoteAddress.get).send(requestBuilder.build, None)
    } else {
      val invocation = new MessageInvocation(this, message, None, sender, currentTransaction.get)
      if (_isEventBased) {
        _mailbox.add(invocation)
        if (_isSuspended) invocation.send
      } 
      else
        invocation.send
    }
  }

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
      message: Any, 
      timeout: Long,
      senderFuture: Option[CompletableFutureResult]): CompletableFutureResult = {
    if (_remoteAddress.isDefined) {
      val requestBuilder = RemoteRequest.newBuilder
          .setId(RemoteRequestIdFactory.nextId)
          .setTarget(this.getClass.getName)
          .setTimeout(this.timeout)
          .setUuid(this.id)
          .setIsActor(true)
          .setIsOneWay(false)
          .setIsEscaped(false)
      RemoteProtocolBuilder.setMessage(message, requestBuilder)
      val id = registerSupervisorAsRemoteActor
      if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)
      val future = RemoteClient.clientFor(_remoteAddress.get).send(requestBuilder.build, senderFuture)
      if (future.isDefined) future.get
      else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
    } else {
      val future = if (senderFuture.isDefined) senderFuture.get
                   else new DefaultCompletableFutureResult(timeout)
      val invocation = new MessageInvocation(this, message, Some(future), None, currentTransaction.get)
      if (_isEventBased) {
        _mailbox.add(invocation)
        invocation.send
      } else invocation.send
      future
    }
  }

  /**
   * Callback for the dispatcher. E.g. single entry point to the user code and all protected[this] methods.
   */
  private[akka] def invoke(messageHandle: MessageInvocation) = synchronized {
    try {
      if (TransactionManagement.isTransactionalityEnabled) transactionalDispatch(messageHandle)
      else dispatch(messageHandle)
    } catch {
      case e =>
        Actor.log.error(e, "Could not invoke actor [%s]", this)
        throw e
    }
  }

  private def dispatch[T](messageHandle: MessageInvocation) = {
    setTransaction(messageHandle.tx)

    val message = messageHandle.message //serializeMessage(messageHandle.message)
    senderFuture = messageHandle.future
    sender = messageHandle.sender

    try {
      if (base.isDefinedAt(message)) base(message) // invoke user actor's receive partial function
      else throw new IllegalArgumentException("No handler matching message [" + message + "] in " + toString)
    } catch {
      case e =>
        _isKilled = true
        Actor.log.error(e, "Could not invoke actor [%s]", this)
        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (_supervisor.isDefined) _supervisor.get ! Exit(this, e)
        if (senderFuture.isDefined) senderFuture.get.completeWithException(this, e)
    } finally {
      clearTransaction
    }
  }

  private def transactionalDispatch[T](messageHandle: MessageInvocation) = {
    setTransaction(messageHandle.tx)

    val message = messageHandle.message //serializeMessage(messageHandle.message)
    senderFuture = messageHandle.future
    sender = messageHandle.sender

    def proceed = {
      try {
        incrementTransaction
        if (base.isDefinedAt(message)) base(message) // invoke user actor's receive partial function
        else throw new IllegalArgumentException(
          "Actor " + toString + " could not process message [" + message + "]" +
           "\n\tsince no matching 'case' clause in its 'receive' method could be found")
      } finally {
        decrementTransaction
      }
    }

    try {
      if (isTransactionRequiresNew && !isTransactionInScope) {
        if (senderFuture.isEmpty) throw new StmException(
          "Can't continue transaction in a one-way fire-forget message send" +
          "\n\tE.g. using Actor '!' method or Active Object 'void' method" +
          "\n\tPlease use the Actor '!!' method or Active Object method with non-void return type")
        atomic {
          proceed
        }
      } else proceed
    } catch {
      case e =>
        Actor.log.error(e, "Exception when invoking \n\tactor [%s] \n\twith message [%s]", this, message)
        if (senderFuture.isDefined) senderFuture.get.completeWithException(this, e)
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
    case HotSwap(code) =>      _hotswap = code
    case Restart(reason) =>    restart(reason)
    case Exit(dead, reason) => handleTrapExit(dead, reason)
    case Kill =>               throw new ActorKilledException("Actor [" + toString + "] was killed by a Kill message")
  }

  private[this] def handleTrapExit(dead: Actor, reason: Throwable): Unit = {
    if (trapExit.exists(_.isAssignableFrom(reason.getClass))) {
      if (faultHandler.isDefined) {
        faultHandler.get match {
          // FIXME: implement support for maxNrOfRetries and withinTimeRange in RestartStrategy
          case AllForOneStrategy(maxNrOfRetries, withinTimeRange) => restartLinkedActors(reason)
          case OneForOneStrategy(maxNrOfRetries, withinTimeRange) => dead.restart(reason)
        }
      } else throw new IllegalStateException(
        "No 'faultHandler' defined for an actor with the 'trapExit' member field defined " +
        "\n\tto non-empty list of exception classes - can't proceed " + toString)
    } else {
      if (_supervisor.isDefined) _supervisor.get ! Exit(dead, reason) // if 'trapExit' is not defined then pass the Exit on
    }
  }

  private[this] def restartLinkedActors(reason: Throwable) = {
    getLinkedActors.toArray.toList.asInstanceOf[List[Actor]].foreach {
      actor =>
        if (actor.lifeCycle.isEmpty) actor.lifeCycle = Some(LifeCycle(Permanent))
        actor.lifeCycle.get match {
          case LifeCycle(scope, _) => {
            scope match {
              case Permanent =>
                actor.restart(reason)
              case Temporary =>
                Actor.log.info("Actor [%s] configured as TEMPORARY and will not be restarted.", actor.id)
                getLinkedActors.remove(actor) // remove the temporary actor
                actor.stop
            }
          }
        }
    }
  }

  private[Actor] def restart(reason: Throwable) = synchronized {
    preRestart(reason)
    Actor.log.info("Restarting actor [%s] configured as PERMANENT.", id)
    postRestart(reason)
    _isKilled = false
  }

  private[akka] def registerSupervisorAsRemoteActor: Option[String] = synchronized {
    if (_supervisor.isDefined) {
      RemoteClient.clientFor(_remoteAddress.get).registerSupervisorForActor(this)
      Some(_supervisor.get.uuid)
    } else None
  }

  protected def getLinkedActors: HashSet[Actor] = {
    if (_linkedActors.isEmpty) {
      val set = new HashSet[Actor]
      _linkedActors = Some(set)
      set
    } else _linkedActors.get
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
        !message.isInstanceOf[Tuple2[_, _]] &&
        !message.isInstanceOf[Tuple3[_, _, _]] &&
        !message.isInstanceOf[Tuple4[_, _, _, _]] &&
        !message.isInstanceOf[Tuple5[_, _, _, _, _]] &&
        !message.isInstanceOf[Tuple6[_, _, _, _, _, _]] &&
        !message.isInstanceOf[Tuple7[_, _, _, _, _, _, _]] &&
        !message.isInstanceOf[Tuple8[_, _, _, _, _, _, _, _]] &&
        !message.getClass.isArray &&
        !message.isInstanceOf[List[_]] &&
        !message.isInstanceOf[scala.collection.immutable.Map[_, _]] &&
        !message.isInstanceOf[scala.collection.immutable.Set[_]] &&
        !message.isInstanceOf[scala.collection.immutable.Tree[_, _]] &&
        !message.getClass.isAnnotationPresent(Annotations.immutable)) {
      Serializer.Java.deepClone(message)
    } else message
  } else message

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, _uuid)
    result
  }

  override def equals(that: Any): Boolean = {
    that != null &&
    that.isInstanceOf[Actor] &&
    that.asInstanceOf[Actor]._uuid == _uuid
  }

  override def toString(): String = "Actor[" + id + ":" + uuid + "]"

}
