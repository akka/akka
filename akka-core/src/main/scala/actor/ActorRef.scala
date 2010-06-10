/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.Config.config
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.stm.Transaction.Global._
import se.scalablesolutions.akka.stm.TransactionManagement._
import se.scalablesolutions.akka.stm.TransactionManagement
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.remote.{RemoteNode, RemoteServer, RemoteClient, RemoteProtocolBuilder, RemoteRequestProtocolIdFactory}
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util.{HashCode, Logging, UUID, ReentrantGuard}

import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.commitbarriers.CountDownCommitBarrier

import jsr166x.{Deque, ConcurrentLinkedDeque}

import java.net.InetSocketAddress
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.{Map => JMap}
import java.lang.reflect.Field

/**
 * The ActorRef object can be used to deserialize ActorRef instances from of its binary representation
 * or its Protocol Buffers (protobuf) Message representation to a Actor.actorOf instance.
 * <p/>
 * Binary -> ActorRef:
 * <pre>
 *   val actorRef = ActorRef.fromBinary(bytes)
 *   actorRef ! message // send message to remote actor through its reference
 * </pre>
 * <p/>
 * Protobuf Message -> ActorRef:
 * <pre>
 *   val actorRef = ActorRef.fromProtobuf(protobufMessage)
 *   actorRef ! message // send message to remote actor through its reference
 * </pre>
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActorRef {

  /**
   * Deserializes the ActorRef instance from a byte array (Array[Byte]) into an ActorRef instance.
   */
  def fromBinary(bytes: Array[Byte]): ActorRef =
    fromProtobuf(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, None)

  def fromBinary(bytes: Array[Byte], loader: ClassLoader): ActorRef =
    fromProtobuf(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, Some(loader))

  /**
   * Deserializes the ActorRef instance from a Protocol Buffers (protobuf) Message into an ActorRef instance.
   */
  private[akka] def fromProtobuf(protocol: RemoteActorRefProtocol, loader: Option[ClassLoader]): ActorRef =
    RemoteActorRef(
      protocol.getUuid,
      protocol.getActorClassName,
      protocol.getHomeAddress.getHostname,
      protocol.getHomeAddress.getPort,
      protocol.getTimeout,
      loader)
}

/**
 * ActorRef is an immutable and serializable handle to an Actor.
 * <p/>
 * Create an ActorRef for an Actor by using the factory method on the Actor object.
 * <p/>
 * Here is an example on how to create an actor with a default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf[MyActor]
 *   actor.start
 *   actor ! message
 *   actor.stop
 * </pre>
 *
 * You can also create and start actors like this:
 * <pre>
 *   val actor = actorOf[MyActor].start
 * </pre>
 *
 * Here is an example on how to create an actor with a non-default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf(new MyActor(...))
 *   actor.start
 *   actor ! message
 *   actor.stop
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ActorRef extends TransactionManagement {

  // Only mutable for RemoteServer in order to maintain identity across nodes
  @volatile protected[akka] var _uuid = UUID.newUuid.toString
  @volatile protected[this] var _isRunning = false
  @volatile protected[this] var _isShutDown = false
  @volatile protected[akka] var _isBeingRestarted = false
  @volatile protected[akka] var _homeAddress = new InetSocketAddress(RemoteServer.HOSTNAME, RemoteServer.PORT)

  @volatile protected[akka] var startOnCreation = false
  @volatile protected[akka] var registeredInRemoteNodeDuringSerialization = false
  protected[this] val guard = new ReentrantGuard

  /**
   * User overridable callback/setting.
   * <p/>
   * Identifier for actor, does not have to be a unique one. Default is the 'uuid'.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor(id), identifier for remote
   * actor in RemoteServer etc.But also as the identifier for persistence, which means
   * that you can use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  @volatile var id: String = _uuid

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  @volatile var timeout: Long = Actor.TIMEOUT

  /**
   * User overridable callback/setting.
   * <p/>
   * Set trapExit to the list of exception classes that the actor should be able to trap
   * from the actor it is supervising. When the supervising actor throws these exceptions
   * then they will trigger a restart.
   * <p/>
   * <pre>
   * // trap no exceptions
   * trapExit = Nil
   *
   * // trap all exceptions
   * trapExit = List(classOf[Throwable])
   *
   * // trap specific exceptions only
   * trapExit = List(classOf[MyApplicationException], classOf[MyApplicationError])
   * </pre>
   */
  @volatile var trapExit: List[Class[_ <: Throwable]] = Nil

  /**
   * User overridable callback/setting.
   * <p/>
   * If 'trapExit' is set for the actor to act as supervisor, then a faultHandler must be defined.
   * Can be one of:
   * <pre/>
   *  faultHandler = Some(AllForOneStrategy(maxNrOfRetries, withinTimeRange))
   *
   *  faultHandler = Some(OneForOneStrategy(maxNrOfRetries, withinTimeRange))
   * </pre>
   */
  @volatile var faultHandler: Option[FaultHandlingStrategy] = None

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the life-cycle for a supervised actor.
   */
  @volatile var lifeCycle: Option[LifeCycle] = None

  /**
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
  private[akka] var _dispatcher: MessageDispatcher = Dispatchers.globalExecutorBasedEventDrivenDispatcher

  /**
   * Holds the hot swapped partial function.
   */
  protected[akka] var hotswap: Option[PartialFunction[Any, Unit]] = None // FIXME: _hotswap should be a stack

  /**
   * User overridable callback/setting.
   * <p/>
   * Set to true if messages should have REQUIRES_NEW semantics, e.g. a new transaction should
   * start if there is no one running, else it joins the existing transaction.
   */
  @volatile protected[akka] var isTransactor = false

  /**v
   * This lock ensures thread safety in the dispatching: only one message can
   * be dispatched at once on the actor.
   */
  protected[akka] val dispatcherLock = new ReentrantLock

  protected[akka] var _sender: Option[ActorRef] = None
  protected[akka] var _senderFuture: Option[CompletableFuture[Any]] = None
  protected[akka] def sender_=(s: Option[ActorRef]) = guard.withGuard { _sender = s }
  protected[akka] def senderFuture_=(sf: Option[CompletableFuture[Any]]) =  guard.withGuard { _senderFuture = sf }

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def sender: Option[ActorRef] = guard.withGuard { _sender }

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '!!' or '!!!', else None.
   */
  def senderFuture: Option[CompletableFuture[Any]] =  guard.withGuard { _senderFuture }

  /**
   * Is the actor being restarted?
   */
  def isBeingRestarted: Boolean = _isBeingRestarted

  /**
   * Is the actor running?
   */
  def isRunning: Boolean = _isRunning

  /**
   * Is the actor shut down?
   */
  def isShutdown: Boolean = _isShutDown

  /**
   * Returns the uuid for the actor.
   */
  def uuid = _uuid

  /**
   * Tests if the actor is able to handle the message passed in as arguments.
   */
  def isDefinedAt(message: Any): Boolean = actor.base.isDefinedAt(message)
  
  /**
   * Only for internal use. UUID is effectively final.
   */
  protected[akka] def uuid_=(uid: String) = _uuid = uid

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   *
   * If invoked from within an actor then the actor reference is implicitly passed on as the implicit 'sender' argument.
   * <p/>
   *
   * This actor 'sender' reference is then available in the receiving actor in the 'sender' member variable,
   * if invoked from within an Actor. If not then no sender is available.
   * <pre>
   *   actor ! message
   * </pre>
   * <p/>
   */
  def !(message: Any)(implicit sender: Option[ActorRef] = None) = {
    if (isRunning) postMessageToMailbox(message, sender)
    else throw new ActorInitializationException(
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
   * If you are sending messages using <code>!!</code> then you <b>have to</b> use <code>self.reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !![T](message: Any, timeout: Long = this.timeout)(implicit sender: Option[ActorRef] = None): Option[T] = {
    if (isRunning) {
      val future = postMessageToMailboxAndCreateFutureResultWithTimeout[T](message, timeout, sender, None)
      val isActiveObject = message.isInstanceOf[Invocation]
      if (isActiveObject && message.asInstanceOf[Invocation].isVoid) {
        future.asInstanceOf[CompletableFuture[Option[_]]].completeWithResult(None)
      }
      try {
        future.await
      } catch {
        case e: FutureTimeoutException =>
          if (isActiveObject) throw e
          else None
      }
      if (future.exception.isDefined) throw future.exception.get._2
      else future.result
    } else throw new ActorInitializationException(
        "Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use '!' together with the 'sender' member field to
   * implement request/response message exchanges.
   * If you are sending messages using <code>!!!</code> then you <b>have to</b> use <code>self.reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !!![T](message: Any)(implicit sender: Option[ActorRef] = None): Future[T] = {
    if (isRunning) postMessageToMailboxAndCreateFutureResultWithTimeout[T](message, timeout, sender, None)
    else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!', '!!' and '!!!'.
   */
  def forward(message: Any)(implicit sender: Some[ActorRef]) = {
    if (isRunning) {
      if (sender.get.senderFuture.isDefined) postMessageToMailboxAndCreateFutureResultWithTimeout(
        message, timeout, sender.get.sender, sender.get.senderFuture)
      else if (sender.get.sender.isDefined) postMessageToMailbox(message, Some(sender.get.sender.get))
      else throw new IllegalStateException("Can't forward message when initial sender is not an actor")
    } else throw new ActorInitializationException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Use <code>self.reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def reply(message: Any) = if(!reply_?(message)) throw new IllegalStateException(
    "\n\tNo sender in scope, can't reply. " +
    "\n\tYou have probably: " +
    "\n\t\t1. Sent a message to an Actor from an instance that is NOT an Actor." +
    "\n\t\t2. Invoked a method on an Active Object from an instance NOT an Active Object." +
    "\n\tElse you might want to use 'reply_?' which returns Boolean(true) if succes and Boolean(false) if no sender in scope")

  /**
   * Use <code>reply_?(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  def reply_?(message: Any): Boolean = {
    if (senderFuture.isDefined) {
      senderFuture.get completeWithResult message
      true
    } else if (sender.isDefined) {
      sender.get ! message
      true
    } else false
  }

  /**
   * Serializes the ActorRef instance into a byte array (Array[Byte]).
   */
  def toBinary: Array[Byte]

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def actorClass: Class[_ <: Actor]

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking:
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   */
  def makeTransactionRequired: Unit

  /**
   * Returns the home address and port for this actor.
   */
  def homeAddress: InetSocketAddress = _homeAddress

  /**
   * Set the home address and port for this actor.
   */
  def homeAddress_=(hostnameAndPort: Tuple2[String, Int]): Unit =
    homeAddress_=(new InetSocketAddress(hostnameAndPort._1, hostnameAndPort._2))

  /**
   * Set the home address and port for this actor.
   */
  def homeAddress_=(address: InetSocketAddress): Unit

  /**
   * Returns the remote address for the actor, if any, else None.
   */
  def remoteAddress: Option[InetSocketAddress]
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit

  /**
   * Starts up the actor and its message queue.
   */
  def start: ActorRef

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Alias for 'stop'.
   */
  def exit = stop

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop: Unit

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
  def link(actorRef: ActorRef): Unit

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def unlink(actorRef: ActorRef): Unit

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLink(actorRef: ActorRef): Unit

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawn[T <: Actor : Manifest]: ActorRef

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLink[T <: Actor: Manifest]: ActorRef

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLinkRemote[T <: Actor : Manifest](hostname: String, port: Int): ActorRef

  /**
   * Returns the mailbox size.
   */
  def mailboxSize: Int

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef]

  /**
   * Shuts down and removes all linked actors.
   */
  def shutdownLinkedActors: Unit

  protected[akka] def toProtobuf: RemoteActorRefProtocol

  protected[akka] def invoke(messageHandle: MessageInvocation): Unit

  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
      message: Any,
      timeout: Long,
      senderOption: Option[ActorRef],
      senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T]

  protected[this] def actorInstance: AtomicReference[Actor]

  protected[akka] def actor: Actor = actorInstance.get

  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit

  protected[akka] def mailbox: Deque[MessageInvocation]

  protected[akka] def restart(reason: Throwable): Unit

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit

  protected[akka] def restartLinkedActors(reason: Throwable): Unit

  protected[akka] def registerSupervisorAsRemoteActor: Option[String]

  protected[akka] def linkedActors: JMap[String, ActorRef]

  protected[akka] def linkedActorsAsList: List[ActorRef]

  override def hashCode: Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that != null &&
    that.isInstanceOf[ActorRef] &&
    that.asInstanceOf[ActorRef].uuid == uuid
  }

  override def toString = "Actor[" + id + ":" + uuid + "]"

  protected def processSender(senderOption: Option[ActorRef], requestBuilder: RemoteRequestProtocol.Builder) = {
    senderOption.foreach { sender =>
      RemoteServer.getOrCreateServer(sender.homeAddress).register(sender.uuid, sender)
      requestBuilder.setSender(sender.toProtobuf)
    }
  }
}

/**
 * Local ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed class LocalActorRef private[akka](
  private[this] var actorFactory: Either[Option[Class[_ <: Actor]], Option[() => Actor]] = Left(None))
  extends ActorRef {

  private[akka] def this(clazz: Class[_ <: Actor]) = this(Left(Some(clazz)))
  private[akka] def this(factory: () => Actor) =     this(Right(Some(factory)))

  // Only mutable for RemoteServer in order to maintain identity across nodes
  @volatile private[akka] var _remoteAddress: Option[InetSocketAddress] = None
  @volatile private[akka] var _linkedActors: Option[ConcurrentHashMap[String, ActorRef]] = None
  @volatile private[akka] var _supervisor: Option[ActorRef] = None

  protected[akka] val _mailbox: Deque[MessageInvocation] = new ConcurrentLinkedDeque[MessageInvocation]
  protected[this] val actorInstance = guard.withGuard { new AtomicReference[Actor](newActor) }

  @volatile private var isInInitialization = false
  @volatile private var runActorInitialization = false

  // Needed to be able to null out the 'val self: ActorRef' member variables to make the Actor
  // instance elegible for garbage collection
  private val actorSelfFields = findActorSelfField(actor.getClass)

  if (runActorInitialization) initializeActorInstance

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  protected[akka] def toProtobuf: RemoteActorRefProtocol = guard.withGuard {
    val host = homeAddress.getHostName
    val port = homeAddress.getPort

    if (!registeredInRemoteNodeDuringSerialization) {
      Actor.log.debug("Register serialized Actor [%s] as remote @ [%s:%s]", actorClass.getName, host, port)
      RemoteServer.getOrCreateServer(homeAddress)
      RemoteServer.registerActor(homeAddress, uuid, this)
      registeredInRemoteNodeDuringSerialization = true
    }
    RemoteActorRefProtocol.newBuilder
      .setUuid(uuid)
      .setActorClassName(actorClass.getName)
      .setHomeAddress(AddressProtocol.newBuilder.setHostname(host).setPort(port).build)
      .setTimeout(timeout)
      .build
  }

  /**
   * Returns the mailbox.
   */
  protected[akka] def mailbox: Deque[MessageInvocation] = _mailbox

  /**
   * Serializes the ActorRef instance into a byte array (Array[Byte]).
   */
  def toBinary: Array[Byte] = toProtobuf.toByteArray

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def actorClass: Class[_ <: Actor] = actor.getClass.asInstanceOf[Class[_ <: Actor]]

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit = guard.withGuard {
    if (!isRunning || isBeingRestarted) _dispatcher = md
    else throw new ActorInitializationException(
      "Can not swap dispatcher for " + toString + " after it has been started")
  }

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher = guard.withGuard { _dispatcher }

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit =
    if (!isRunning || isBeingRestarted) makeRemote(new InetSocketAddress(hostname, port))
    else throw new ActorInitializationException(
      "Can't make a running actor remote. Make sure you call 'makeRemote' before 'start'.")

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit = guard.withGuard {
    if (!isRunning || isBeingRestarted) {
      _remoteAddress = Some(address)
      RemoteClient.register(address.getHostName, address.getPort, uuid)
      homeAddress = (RemoteServer.HOSTNAME, RemoteServer.PORT)
    } else throw new ActorInitializationException(
      "Can't make a running actor remote. Make sure you call 'makeRemote' before 'start'.")
  }

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking:
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   */
  def makeTransactionRequired = guard.withGuard {
    if (!isRunning || isBeingRestarted) isTransactor = true
    else throw new ActorInitializationException(
      "Can not make actor transaction required after it has been started")
  }

  /**
   * Set the contact address for this actor. This is used for replying to messages
   * sent asynchronously when no reply channel exists.
   */
  def homeAddress_=(address: InetSocketAddress): Unit = guard.withGuard { _homeAddress = address }

  /**
   * Returns the remote address for the actor, if any, else None.
   */
  def remoteAddress: Option[InetSocketAddress] = guard.withGuard { _remoteAddress }
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit = guard.withGuard { _remoteAddress = addr }

  /**
   * Starts up the actor and its message queue.
   */
  def start: ActorRef = guard.withGuard {
    if (isShutdown) throw new ActorStartException(
      "Can't restart an actor that has been shut down with 'stop' or 'exit'")
    if (!isRunning) {
      dispatcher.register(this)
      dispatcher.start
      _isRunning = true
      if (!isInInitialization) initializeActorInstance
      else runActorInitialization = true
    }
    this
  }

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop = guard.withGuard {
    if (isRunning) {
      dispatcher.unregister(this)
      _isRunning = false
      _isShutDown = true
      actor.shutdown
      ActorRegistry.unregister(this)
      remoteAddress.foreach(address => RemoteClient.unregister(
        address.getHostName, address.getPort, uuid))
      RemoteNode.unregister(this)
      nullOutActorRefReferencesFor(actorInstance.get)
    } else if (isBeingRestarted) throw new ActorKilledException("Actor [" + toString + "] is being restarted.")
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
  def link(actorRef: ActorRef) = guard.withGuard {
    if (actorRef.supervisor.isDefined) throw new IllegalStateException(
      "Actor can only have one supervisor [" + actorRef + "], e.g. link(actor) fails")
    linkedActors.put(actorRef.uuid, actorRef)
    actorRef.supervisor = Some(this)
    Actor.log.debug("Linking actor [%s] to actor [%s]", actorRef, this)
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def unlink(actorRef: ActorRef) = guard.withGuard {
    if (!linkedActors.containsKey(actorRef.uuid)) throw new IllegalStateException(
      "Actor [" + actorRef + "] is not a linked actor, can't unlink")
    linkedActors.remove(actorRef.uuid)
    actorRef.supervisor = None
    Actor.log.debug("Unlinking actor [%s] from actor [%s]", actorRef, this)
  }

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLink(actorRef: ActorRef) = guard.withGuard {
    try {
      actorRef.start
    } finally {
      link(actorRef)
    }
  }

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int) = guard.withGuard {
    try {
      actorRef.makeRemote(hostname, port)
      actorRef.start
    } finally {
      link(actorRef)
    }
  }

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawn[T <: Actor : Manifest]: ActorRef = guard.withGuard {
    val actorRef = spawnButDoNotStart[T]
    actorRef.start
    actorRef
  }

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef = guard.withGuard {
    val actor = spawnButDoNotStart[T]
    actor.makeRemote(hostname, port)
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLink[T <: Actor: Manifest]: ActorRef = guard.withGuard {
    val actor = spawnButDoNotStart[T]
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
  def spawnLinkRemote[T <: Actor : Manifest](hostname: String, port: Int): ActorRef = guard.withGuard {
    val actor = spawnButDoNotStart[T]
    try {
      actor.makeRemote(hostname, port)
      actor.start
    } finally {
      link(actor)
    }
  }

  /**
   * Returns the mailbox size.
   */
  def mailboxSize: Int = _mailbox.size

  /**
   * Returns a copy of all the messages, put into a List[MessageInvocation].
   */
  def messagesInMailbox: List[MessageInvocation] = _mailbox.toArray.toList.asInstanceOf[List[MessageInvocation]]

  /**
   * Shuts down and removes all linked actors.
   */
  def shutdownLinkedActors: Unit = guard.withGuard {
    linkedActorsAsList.foreach(_.stop)
    linkedActors.clear
  }

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef] = guard.withGuard { _supervisor }

  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = guard.withGuard { _supervisor = sup }

  private def spawnButDoNotStart[T <: Actor: Manifest]: ActorRef = guard.withGuard {
    val actorRef = Actor.actorOf(manifest[T].erasure.asInstanceOf[Class[T]].newInstance)
    if (!dispatcher.isInstanceOf[ThreadBasedDispatcher]) actorRef.dispatcher = dispatcher
    actorRef
  }

  private[this] def newActor: Actor = {
    isInInitialization = true
    Actor.actorRefInCreation.value = Some(this)
    val actor = actorFactory match {
      case Left(Some(clazz)) =>
        try {
          clazz.newInstance
        } catch {
          case e: InstantiationException => throw new ActorInitializationException(
            "Could not instantiate Actor due to:\n" + e +
            "\nMake sure Actor is NOT defined inside a class/trait," +
            "\nif so put it outside the class/trait, f.e. in a companion object," +
            "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.")
        }
      case Right(Some(factory)) =>
        factory()
      case _ =>
        throw new ActorInitializationException(
          "Can't create Actor, no Actor class or factory function in scope")
    }
    if (actor eq null) throw new ActorInitializationException(
      "Actor instance passed to ActorRef can not be 'null'")
    isInInitialization = false
    actor
  }

  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = {
    joinTransaction(message)

    if (remoteAddress.isDefined) {
      val requestBuilder = RemoteRequestProtocol.newBuilder
          .setId(RemoteRequestProtocolIdFactory.nextId)
          .setTarget(actorClass.getName)
          .setTimeout(timeout)
          .setUuid(uuid)
          .setIsActor(true)
          .setIsOneWay(true)
          .setIsEscaped(false)

      val id = registerSupervisorAsRemoteActor
      if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)
      processSender(senderOption, requestBuilder)

      RemoteProtocolBuilder.setMessage(message, requestBuilder)
      RemoteClient.clientFor(remoteAddress.get).send[Any](requestBuilder.build, None)
    } else {
      val invocation = new MessageInvocation(this, message, senderOption, None, transactionSet.get)
      if (dispatcher.usesActorMailbox) {
        _mailbox.add(invocation)
        invocation.send
      } else invocation.send
    }
  }

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
      message: Any,
      timeout: Long,
      senderOption: Option[ActorRef],
      senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = {
    joinTransaction(message)

    if (remoteAddress.isDefined) {
      val requestBuilder = RemoteRequestProtocol.newBuilder
          .setId(RemoteRequestProtocolIdFactory.nextId)
          .setTarget(actorClass.getName)
          .setTimeout(timeout)
          .setUuid(uuid)
          .setIsActor(true)
          .setIsOneWay(false)
          .setIsEscaped(false)

      //senderOption.foreach(sender => requestBuilder.setSender(sender.toProtobuf))
      RemoteProtocolBuilder.setMessage(message, requestBuilder)

      val id = registerSupervisorAsRemoteActor
      if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)

      val future = RemoteClient.clientFor(remoteAddress.get).send(requestBuilder.build, senderFuture)
      if (future.isDefined) future.get
      else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
    } else {
      val future = if (senderFuture.isDefined) senderFuture.get
                   else new DefaultCompletableFuture[T](timeout)
      val invocation = new MessageInvocation(
        this, message, senderOption, Some(future.asInstanceOf[CompletableFuture[Any]]), transactionSet.get)
      if (dispatcher.usesActorMailbox) _mailbox.add(invocation)
      invocation.send
      future
    }
  }

  private def joinTransaction(message: Any) = if (isTransactionSetInScope) {
    import org.multiverse.api.ThreadLocalTransaction
    val txSet = getTransactionSetInScope
    Actor.log.trace("Joining transaction set [%s];\n\tactor %s\n\twith message [%s]", txSet, toString, message) // FIXME test to run bench without this trace call
    val mtx = ThreadLocalTransaction.getThreadLocalTransaction
    if ((mtx eq null) || mtx.getStatus.isDead) txSet.incParties
    else txSet.incParties(mtx, 1)
  }

  /**
   * Callback for the dispatcher. This is the ingle entry point to the user Actor implementation.
   */
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = actor.synchronized {
    if (isShutdown) {
      Actor.log.warning("Actor [%s] is shut down, ignoring message [%s]", toString, messageHandle)
      return
    }
    sender = messageHandle.sender
    senderFuture = messageHandle.senderFuture
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
    val message = messageHandle.message //serializeMessage(messageHandle.message)
    setTransactionSet(messageHandle.transactionSet)
    try {
      actor.base(message)
    } catch {
      case e =>
        _isBeingRestarted = true
        Actor.log.error(e, "Could not invoke actor [%s]", toString)
        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (_supervisor.isDefined) _supervisor.get ! Exit(this, e)
        senderFuture.foreach(_.completeWithException(this, e))
    } finally {
      clearTransaction
    }
  }

  private def transactionalDispatch[T](messageHandle: MessageInvocation) = {
    val message = messageHandle.message //serializeMessage(messageHandle.message)
    var topLevelTransaction = false
    val txSet: Option[CountDownCommitBarrier] =
      if (messageHandle.transactionSet.isDefined) messageHandle.transactionSet
      else {
        topLevelTransaction = true // FIXME create a new internal atomic block that can wait for X seconds if top level tx
        if (isTransactor) {
          Actor.log.trace(
            "Creating a new transaction set (top-level transaction)\n\tfor actor %s\n\twith message %s",
            toString, messageHandle)
          Some(createNewTransactionSet)
        } else None
      }
    setTransactionSet(txSet)

    try {
      if (isTransactor) {
        atomic {
          actor.base(message)
          setTransactionSet(txSet) // restore transaction set to allow atomic block to do commit
        }
      } else {
        actor.base(message)
        setTransactionSet(txSet) // restore transaction set to allow atomic block to do commit
      }
    } catch {
      case e: IllegalStateException => {}
      case e =>
        _isBeingRestarted = true
        // abort transaction set
        if (isTransactionSetInScope) try {
          val txSet = getTransactionSetInScope
          Actor.log.debug("Aborting transaction set [%s]", txSet)
          txSet.abort
        } catch { case e: IllegalStateException => {} }
        Actor.log.error(e, "Exception when invoking \n\tactor [%s] \n\twith message [%s]", this, message)

        senderFuture.foreach(_.completeWithException(this, e))

        clearTransaction
        if (topLevelTransaction) clearTransactionSet

        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (_supervisor.isDefined) _supervisor.get ! Exit(this, e)
    } finally {
      clearTransaction
      if (topLevelTransaction) clearTransactionSet
    }
  }

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = {
    if (trapExit.exists(_.isAssignableFrom(reason.getClass))) {
      if (faultHandler.isDefined) {
        faultHandler.get match {
          // FIXME: implement support for maxNrOfRetries and withinTimeRange in RestartStrategy
          case AllForOneStrategy(maxNrOfRetries, withinTimeRange) =>
            restartLinkedActors(reason)

          case OneForOneStrategy(maxNrOfRetries, withinTimeRange) =>
            dead.restart(reason)
        }
      } else throw new IllegalStateException(
        "No 'faultHandler' defined for an actor with the 'trapExit' member field defined " +
        "\n\tto non-empty list of exception classes - can't proceed " + toString)
    } else {
      _supervisor.foreach(_ ! Exit(dead, reason)) // if 'trapExit' is not defined then pass the Exit on
    }
  }

  protected[akka] def restart(reason: Throwable): Unit = {
    //_isBeingRestarted = true
    Actor.log.info("Restarting actor [%s] configured as PERMANENT.", id)
    restartLinkedActors(reason)
    val failedActor = actorInstance.get
    failedActor.synchronized {
      Actor.log.debug("Restarting linked actors for actor [%s].", id)
      Actor.log.debug("Invoking 'preRestart' for failed actor instance [%s].", id)
      failedActor.preRestart(reason)
      nullOutActorRefReferencesFor(failedActor)
      val freshActor = newActor
      freshActor.synchronized {
        freshActor.init
        freshActor.initTransactionalState
        actorInstance.set(freshActor)
        Actor.log.debug("Invoking 'postRestart' for new actor instance [%s].", id)
        freshActor.postRestart(reason)
      }
      _isBeingRestarted = false
    }
  }

  protected[akka] def restartLinkedActors(reason: Throwable) = guard.withGuard {
    linkedActorsAsList.foreach { actorRef =>
      if (actorRef.lifeCycle.isEmpty) actorRef.lifeCycle = Some(LifeCycle(Permanent))
      actorRef.lifeCycle.get match {
        case LifeCycle(scope, _) => {
          scope match {
            case Permanent =>
              actorRef.restart(reason)
            case Temporary =>
              Actor.log.info("Actor [%s] configured as TEMPORARY and will not be restarted.", actorRef.id)
              actorRef.stop
              linkedActors.remove(actorRef.uuid) // remove the temporary actor
              // if last temporary actor is gone, then unlink me from supervisor
              if (linkedActors.isEmpty) {
                Actor.log.info(
                  "All linked actors have died permanently (they were all configured as TEMPORARY)" +
                  "\n\tshutting down and unlinking supervisor actor as well [%s].",
                  actorRef.id)
                _supervisor.foreach(_ ! UnlinkAndStop(this))
              }
          }
        }
      }
    }
  }

  protected[akka] def registerSupervisorAsRemoteActor: Option[String] = guard.withGuard {
    if (_supervisor.isDefined) {
      RemoteClient.clientFor(remoteAddress.get).registerSupervisorForActor(this)
      Some(_supervisor.get.uuid)
    } else None
  }

  protected[akka] def linkedActors: JMap[String, ActorRef] = guard.withGuard {
    if (_linkedActors.isEmpty) {
      val actors = new ConcurrentHashMap[String, ActorRef]
      _linkedActors = Some(actors)
      actors
    } else _linkedActors.get
  }

  protected[akka] def linkedActorsAsList: List[ActorRef] =
    linkedActors.values.toArray.toList.asInstanceOf[List[ActorRef]]

  private def nullOutActorRefReferencesFor(actor: Actor) = {
    actorSelfFields._1.set(actor, null)
    actorSelfFields._2.set(actor, null)
    actorSelfFields._3.set(actor, null)
  }

  private def findActorSelfField(clazz: Class[_]): Tuple3[Field, Field, Field] = {
    try {
      val selfField =       clazz.getDeclaredField("self")
      val optionSelfField = clazz.getDeclaredField("optionSelf")
      val someSelfField =   clazz.getDeclaredField("someSelf")
      selfField.setAccessible(true)
      optionSelfField.setAccessible(true)
      someSelfField.setAccessible(true)
      (selfField, optionSelfField, someSelfField)
    } catch {
      case e: NoSuchFieldException =>
        val parent = clazz.getSuperclass
        if (parent != null) findActorSelfField(parent)
        else throw new IllegalStateException(toString + " is not an Actor since it have not mixed in the 'Actor' trait")
    }
  }

  private def initializeActorInstance = {
    actor.init // run actor init and initTransactionalState callbacks
    actor.initTransactionalState
    Actor.log.debug("[%s] has started", toString)
    ActorRegistry.register(this)
    if (id == "N/A") id = actorClass.getName // if no name set, then use default name (class name)
    clearTransactionSet // clear transaction set that might have been created if atomic block has been used within the Actor constructor body
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
        !message.isInstanceOf[scala.collection.immutable.Set[_]]) {
      Serializer.Java.deepClone(message)
    } else message
  } else message
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] case class RemoteActorRef private[akka] (
//  uuid: String, className: String, hostname: String, port: Int, timeOut: Long, isOnRemoteHost: Boolean) extends ActorRef {
  uuuid: String, val className: String, val hostname: String, val port: Int, _timeout: Long, loader: Option[ClassLoader])
  extends ActorRef {
  _uuid = uuuid
  timeout = _timeout

  start
  lazy val remoteClient = RemoteClient.clientFor(hostname, port, loader)

  def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = {
    val requestBuilder = RemoteRequestProtocol.newBuilder
        .setId(RemoteRequestProtocolIdFactory.nextId)
        .setTarget(className)
        .setTimeout(timeout)
        .setUuid(uuid)
        .setIsActor(true)
        .setIsOneWay(true)
        .setIsEscaped(false)
    processSender(senderOption, requestBuilder)
    RemoteProtocolBuilder.setMessage(message, requestBuilder)
    remoteClient.send[Any](requestBuilder.build, None)
  }

  def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
      message: Any,
      timeout: Long,
      senderOption: Option[ActorRef],
      senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = {
    val requestBuilder = RemoteRequestProtocol.newBuilder
        .setId(RemoteRequestProtocolIdFactory.nextId)
        .setTarget(className)
        .setTimeout(timeout)
        .setUuid(uuid)
        .setIsActor(true)
        .setIsOneWay(false)
        .setIsEscaped(false)
    RemoteProtocolBuilder.setMessage(message, requestBuilder)
    val future = remoteClient.send(requestBuilder.build, senderFuture)
    if (future.isDefined) future.get
    else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
  }

  def start: ActorRef = {
    _isRunning = true
    this
  }

  def stop: Unit = {
    _isRunning = false
    _isShutDown = true
  }

  // ==== NOT SUPPORTED ====
  def toBinary: Array[Byte] = unsupported
  def actorClass: Class[_ <: Actor] = unsupported
  def dispatcher_=(md: MessageDispatcher): Unit = unsupported
  def dispatcher: MessageDispatcher = unsupported
  def makeTransactionRequired: Unit = unsupported
  def makeRemote(hostname: String, port: Int): Unit = unsupported
  def makeRemote(address: InetSocketAddress): Unit = unsupported
  def homeAddress_=(address: InetSocketAddress): Unit = unsupported
  def remoteAddress: Option[InetSocketAddress] = unsupported
  def link(actorRef: ActorRef): Unit = unsupported
  def unlink(actorRef: ActorRef): Unit = unsupported
  def startLink(actorRef: ActorRef): Unit = unsupported
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit = unsupported
  def spawn[T <: Actor : Manifest]: ActorRef = unsupported
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef = unsupported
  def spawnLink[T <: Actor: Manifest]: ActorRef = unsupported
  def spawnLinkRemote[T <: Actor : Manifest](hostname: String, port: Int): ActorRef = unsupported
  def mailboxSize: Int = unsupported
  def supervisor: Option[ActorRef] = unsupported
  def shutdownLinkedActors: Unit = unsupported
  protected[akka] def toProtobuf: RemoteActorRefProtocol = unsupported
  protected[akka] def mailbox: Deque[MessageInvocation] = unsupported
  protected[akka] def restart(reason: Throwable): Unit = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = unsupported
  protected[akka] def restartLinkedActors(reason: Throwable): Unit = unsupported
  protected[akka] def registerSupervisorAsRemoteActor: Option[String] = unsupported
  protected[akka] def linkedActors: JMap[String, ActorRef] = unsupported
  protected[akka] def linkedActorsAsList: List[ActorRef] = unsupported
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = unsupported
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit = unsupported
  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = unsupported
  protected[this] def actorInstance: AtomicReference[Actor] = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
}
