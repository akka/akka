/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.stm.Transaction.Global._
import se.scalablesolutions.akka.stm.TransactionManagement._
import se.scalablesolutions.akka.stm.TransactionManagement
import se.scalablesolutions.akka.remote.protobuf.RemoteProtocol.{RemoteRequestProtocol, RemoteReplyProtocol, ActorRefProtocol}
import se.scalablesolutions.akka.remote.{RemoteNode, RemoteServer, RemoteClient, RemoteProtocolBuilder, RemoteRequestProtocolIdFactory}
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util.{HashCode, Logging, UUID}

import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.commitbarriers.CountDownCommitBarrier

import jsr166x.{Deque, ConcurrentLinkedDeque}

import java.net.InetSocketAddress
import java.util.concurrent.locks.{Lock, ReentrantLock}
import java.util.{HashSet => JHashSet}

/**
 * Implements the Transactor abstraction. E.g. a transactional actor.
 * <p/>
 * Equivalent to invoking the <code>makeTransactionRequired</code> method in the body of the <code>Actor</code
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Transactor extends Actor {
  makeTransactionRequired
}

/**
 * Extend this abstract class to create a remote actor.
 * <p/>
 * Equivalent to invoking the <code>makeRemote(..)</code> method in the body of the <code>Actor</code
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class RemoteActor(hostname: String, port: Int) extends Actor {
  makeRemote(hostname, port)
}

// Life-cycle messages for the Actors
@serializable sealed trait LifeCycleMessage
case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends LifeCycleMessage
case class Restart(reason: Throwable) extends LifeCycleMessage
case class Exit(dead: ActorRef, killer: Throwable) extends LifeCycleMessage
case class Unlink(child: ActorRef) extends LifeCycleMessage
case class UnlinkAndStop(child: ActorRef) extends LifeCycleMessage
case object Kill extends LifeCycleMessage

// Exceptions for Actors
class ActorKilledException private[akka](message: String) extends RuntimeException(message)
class ActorInitializationException private[akka](message: String) extends RuntimeException(message)

/**
 * Utility class with factory methods for creating Actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor extends Logging {
  val TIMEOUT =            config.getInt("akka.actor.timeout", 5000)
  val HOSTNAME =           config.getString("akka.remote.server.hostname", "localhost")
  val PORT =               config.getInt("akka.remote.server.port", 9999)
  val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)

  // FIXME remove next release
  object Sender {
    @deprecated("import Actor.Sender.Self is not needed anymore, just use 'actor ! msg'")
    object Self
  }

  /**
   * Creates a new ActorRef out of the Actor with type T.
   * <pre>
   *   import Actor._
   *   val actor = newActor[MyActor]
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   */
  def newActor[T <: Actor: Manifest]: ActorRef = new ActorRef(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Creates a new ActorRef out of the Actor. Allows you to pass in a factory function 
   * that creates the Actor. Please note that this function can be invoked multiple 
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.
   * <pre>
   *   import Actor._
   *   val actor = newActor(() => new MyActor)
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   */
  def newActor(factory: () => Actor): ActorRef = new ActorRef(factory)

  /**
   * Use to create an anonymous event-driven actor.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = actor {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def actor(body: PartialFunction[Any, Unit]): ActorRef =
    newActor(() => new Actor() {
      lifeCycle = Some(LifeCycle(Permanent))
      def receive: PartialFunction[Any, Unit] = body
    }).start

  /**
   * Use to create an anonymous transactional event-driven actor.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = transactor {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def transactor(body: PartialFunction[Any, Unit]): ActorRef =
    newActor(() => new Transactor() {
      lifeCycle = Some(LifeCycle(Permanent))
      def receive: PartialFunction[Any, Unit] = body
    }).start

  /**
   * Use to create an anonymous event-driven actor with a 'temporary' life-cycle configuration,
   * which means that if the actor is supervised and dies it will *not* be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = temporaryActor {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def temporaryActor(body: PartialFunction[Any, Unit]): ActorRef =
    newActor(() => new Actor() {
      lifeCycle = Some(LifeCycle(Temporary))
      def receive = body
    }).start

  /**
   * Use to create an anonymous event-driven actor with both an init block and a message loop block.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * val a = Actor.init {
   *   ... // init stuff
   * } receive  {
   *   case msg => ... // handle message
   * }
   * </pre>
   *
   */
  def init[A](body: => Unit) = {
    def handler[A](body: => Unit) = new {
      def receive(handler: PartialFunction[Any, Unit]) =
        newActor(() => new Actor() {
          lifeCycle = Some(LifeCycle(Permanent))
          body
          def receive = handler
        }).start
    }
    handler(body)
  }

  /**
   * Use to spawn out a block of code in an event-driven actor. Will shut actor down when
   * the block has been executed.
   * <p/>
   * NOTE: If used from within an Actor then has to be qualified with 'Actor.spawn' since
   * there is a method 'spawn[ActorType]' in the Actor trait already.
   * Example:
   * <pre>
   * import Actor._
   *
   * spawn {
   *   ... // do stuff
   * }
   * </pre>
   */
  def spawn(body: => Unit): Unit = {
    case object Spawn
    newActor(() => new Actor() {
      self ! Spawn
      def receive = {
        case Spawn => body; stop
      }
    }).start
  }
}

/**
 * The ActorRef object can be used to deserialize ActorRef instances from of its binary representation
 * or its Protocol Buffers (protobuf) Message representation to a new ActorRef instance.
 * <p/>
 * Binary -> ActorRef:
 * <pre>
 *   val actorRef = ActorRef.fromBinary(bytes)
 *   actorRef ! message // send message to remote actor through its reference
 * </pre>
 * <p/>
 * Protobuf Message -> ActorRef:
 * <pre>
 *   val actorRef = ActorRef.fromProtocol(protobufMessage)
 *   actorRef ! message // send message to remote actor through its reference
 * </pre>
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActorRef {

  /**
   * Deserializes the ActorRef instance from a byte array (Array[Byte]) into an ActorRef instance.
   */
  def fromBinary(bytes: Array[Byte]): ActorRef = 
    fromProtocol(ActorRefProtocol.newBuilder.mergeFrom(bytes).build)

  /**
   * Deserializes the ActorRef instance from a Protocol Buffers (protobuf) Message into an ActorRef instance.
   */
  def fromProtocol(protocol: ActorRefProtocol): ActorRef =
    RemoteActorRef(
      protocol.getUuid,
      protocol.getActorClassName,
      protocol.getSourceHostname,
      protocol.getSourcePort,
      protocol.getTimeout)
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
 *   val actor = newActor[MyActor]
 *   actor.start
 *   actor ! message
 *   actor.stop
 * </pre>
 * Here is an example on how to create an actor with a non-default constructor.
 * <pre>
 *   import Actor._
 * 
 *   val actor = newActor(() => new MyActor(...))
 *   actor.start
 *   actor ! message
 *   actor.stop
 * </pre>
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed class ActorRef private[akka] () extends TransactionManagement {
  import java.util.concurrent.atomic.AtomicReference
  
  // Only mutable for RemoteServer in order to maintain identity across nodes
  private[akka] var _uuid = UUID.newUuid.toString

  @volatile private[this] var _isRunning = false
  @volatile private[this] var _isSuspended = true
  @volatile private[this] var _isShutDown = false
  @volatile private[akka] var _isKilled = false
  @volatile private[akka] var _registeredInRemoteNodeDuringSerialization = false
  private[akka] var _remoteAddress: Option[InetSocketAddress] = None
  private[akka] var _linkedActors: Option[JHashSet[ActorRef]] = None
  private[akka] var _supervisor: Option[ActorRef] = None
  private[akka] var _replyToAddress: Option[InetSocketAddress] = None
  private[akka] val _mailbox: Deque[MessageInvocation] = new ConcurrentLinkedDeque[MessageInvocation]

  /**
   * User overridable callback/setting.
   * <p/>
   * Set to true if messages should have REQUIRES_NEW semantics, e.g. a new transaction should
   * start if there is no one running, else it joins the existing transaction.
   */
  @volatile private[akka] var isTransactor = false

  /**
   * This lock ensures thread safety in the dispatching: only one message can
   * be dispatched at once on the actor.
   */
  private[akka] val _dispatcherLock: Lock = new ReentrantLock

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
  private[akka] var messageDispatcher: MessageDispatcher = Dispatchers.globalExecutorBasedEventDrivenDispatcher

  /**
   * Holds the reference to the sender of the currently processed message.
   * - Is None if no sender was specified
   * - Is Some(Left(Actor)) if sender is an actor
   * - Is Some(Right(CompletableFuture)) if sender is holding on to a Future for the result
   */
  private[akka] var replyTo: Option[Either[ActorRef, CompletableFuture[Any]]] = None

  private[this] var actorFactory: Either[Option[Class[_ <: Actor]], Option[() => Actor]] = Left(None)

  private[this] lazy val actorInstance: AtomicReference[Actor] = new AtomicReference[Actor](newActor)

  private[akka] def actor: Actor = actorInstance.get

  private[akka] def this(clazz: Class[_ <: Actor]) = { 
    this()
    actorFactory = Left(Some(clazz))
  }
  
  private[akka] def this(factory: () => Actor) = {
    this()
    actorFactory = Right(Some(factory))
  }    

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
    if (isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (isRunning) postMessageToMailbox(message, sender)
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
    if (isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (isRunning) {
      val future = postMessageToMailboxAndCreateFutureResultWithTimeout[T](message, timeout, None)
      val isActiveObject = message.isInstanceOf[Invocation]
      if (isActiveObject && message.asInstanceOf[Invocation].isVoid) 
        future.asInstanceOf[CompletableFuture[Option[_]]].completeWithResult(None)
      try {
        future.await
      } catch {
        case e: FutureTimeoutException =>
          if (isActiveObject) throw e
          else None
      }

      if (future.exception.isDefined) throw future.exception.get._2
      else future.result
    }
    else throw new IllegalStateException(
      "Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * Uses the time-out defined in the Actor.
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
  def !![T](message: Any)(implicit sender: Option[ActorRef] = None): Option[T] = !![T](message, timeout)

  /**
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use '!' together with the 'sender' member field to
   * implement request/response message exchanges.
   * If you are sending messages using <code>!!!</code> then you <b>have to</b> use <code>reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def !!![T](message: Any): Future[T] = {
    if (isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (isRunning) postMessageToMailboxAndCreateFutureResultWithTimeout[T](message, timeout, None)
    else throw new IllegalStateException(
      "Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!', '!!' and '!!!'.
   */
  def forward(message: Any)(implicit sender: Some[ActorRef]) = {
    if (isKilled) throw new ActorKilledException("Actor [" + toString + "] has been killed, can't respond to messages")
    if (isRunning) {
      sender.get.replyTo match {
        case Some(Left(actorRef)) => postMessageToMailbox(message, Some(actorRef))
        case Some(Right(future))  => postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, Some(future))
        case _                    => throw new IllegalStateException("Can't forward message when initial sender is not an actor")
      }
    } else throw new IllegalStateException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toProtocol: ActorRefProtocol = {
    val (host, port) = _replyToAddress.map(address => 
      (address.getHostName, address.getPort))
      .getOrElse((Actor.HOSTNAME, Actor.PORT))

    if (!_registeredInRemoteNodeDuringSerialization) { 
      Actor.log.debug("Register serialized Actor [%s] as remote @ [%s:%s]", actorClass.getName, host, port)
      if (RemoteServer.serverFor(host, port).isEmpty) (new RemoteServer).start(host, port)
      RemoteServer.actorsFor(RemoteServer.Address(host, port)).actors.put(uuid, this)
      _registeredInRemoteNodeDuringSerialization = true
    }
    
    ActorRefProtocol.newBuilder
      .setUuid(uuid)
      .setActorClassName(actorClass.getName)
      .setSourceHostname(host)
      .setSourcePort(port)
      .setTimeout(timeout)
      .build
  }
  
  /**
   * Serializes the ActorRef instance into a byte array (Array[Byte]).
   */
  def toBinary: Array[Byte] = toProtocol.toByteArray
  
  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def actorClass: Class[_ <: Actor] = actor.getClass.asInstanceOf[Class[_ <: Actor]]
  
  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit = synchronized {
    if (!isRunning) {
      messageDispatcher.unregister(this)
      messageDispatcher = md
      messageDispatcher.register(this)
    } else throw new IllegalArgumentException(
      "Can not swap dispatcher for " + toString + " after it has been started")
  }

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher = messageDispatcher

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit =
    if (isRunning) throw new IllegalStateException("Can't make a running actor remote. Make sure you call 'makeRemote' before 'start'.")
    else makeRemote(new InetSocketAddress(hostname, port))

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit =
    if (isRunning) throw new IllegalStateException("Can't make a running actor remote. Make sure you call 'makeRemote' before 'start'.")
    else {
      _remoteAddress = Some(address)
      RemoteClient.register(address.getHostName, address.getPort, uuid)
      if (_replyToAddress.isEmpty) setReplyToAddress(Actor.HOSTNAME, Actor.PORT)
  }

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking:
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   */
  def makeTransactionRequired = actor.synchronized {
    if (isRunning) throw new IllegalArgumentException(
      "Can not make actor transaction required after it has been started")
    else isTransactor = true
  }

  /**
   * Set the contact address for this actor. This is used for replying to messages sent asynchronously when no reply channel exists.
   */
  def setReplyToAddress(hostname: String, port: Int): Unit = setReplyToAddress(new InetSocketAddress(hostname, port))

  /**
   * Set the contact address for this actor. This is used for replying to messages sent asynchronously when no reply channel exists.
   */
  def setReplyToAddress(address: InetSocketAddress): Unit = _replyToAddress = Some(address)

  /**
   * Returns the id for the actor.
   */
  def id = actor.getId

  /**
   * Returns the uuid for the actor.
   */
  def uuid = _uuid
  
  /**
   * Returns the remote address for the actor, if any, else None.
   */
  def remoteAddress: Option[InetSocketAddress] = _remoteAddress

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  def timeout: Long = actor.timeout

  /**
   * Sets the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  def timeout_=(t: Long) = actor.timeout = t

  /**
   * Starts up the actor and its message queue.
   */
  def start: ActorRef = synchronized {
    if (_isShutDown) throw new IllegalStateException("Can't restart an actor that has been shut down with 'stop' or 'exit'")
    if (!isRunning) {
      messageDispatcher.register(this)
      messageDispatcher.start
      _isRunning = true
      actor.init
      actor.initTransactionalState
    }
    Actor.log.debug("[%s] has started", toString)
    ActorRegistry.register(this)
    this
  }

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Alias for 'stop'.
   */
  def exit = stop

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop = synchronized {
    if (isRunning) {
      messageDispatcher.unregister(this)
      _isRunning = false
      _isShutDown = true
      actor.shutdown
      ActorRegistry.unregister(this)
      _remoteAddress.foreach(address => RemoteClient.unregister(address.getHostName, address.getPort, uuid))
      RemoteNode.unregister(this)
    }
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
  def link(actorRef: ActorRef) = {
    if (actorRef.supervisor.isDefined) throw new IllegalStateException(
      "Actor can only have one supervisor [" + actorRef + "], e.g. link(actor) fails")
    linkedActors.add(actorRef)
    actorRef.supervisor = Some(this)
    Actor.log.debug("Linking actor [%s] to actor [%s]", actorRef, this)
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def unlink(actorRef: ActorRef) = {
    if (!linkedActors.contains(actorRef)) throw new IllegalStateException(
      "Actor [" + actorRef + "] is not a linked actor, can't unlink")
    linkedActors.remove(actorRef)
    actorRef.supervisor = None
    Actor.log.debug("Unlinking actor [%s] from actor [%s]", actorRef, this)
  }

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLink(actorRef: ActorRef) = {
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
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int) = {
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
  def spawn[T <: Actor : Manifest]: ActorRef = {
    val actorRef = spawnButDoNotStart[T]
    actorRef.start
    actorRef
  }

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef = {
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
  def spawnLink[T <: Actor: Manifest]: ActorRef = {
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
  def spawnLinkRemote[T <: Actor : Manifest](hostname: String, port: Int): ActorRef = {
    val actor = spawnButDoNotStart[T]
    try {
      actor.makeRemote(hostname, port)
      actor.start
    } finally {
      link(actor)
    }
    actor
  }

  /**
   * Is the actor killed?
   */
  def isKilled: Boolean = _isKilled

  /**
   * Is the actor running?
   */
  def isRunning: Boolean = _isRunning

  /**
   * Is the actor shut down?
   */
  def isShutdown: Boolean = !_isRunning

  /**
   * Returns the mailbox size.
   */
  def mailboxSize: Int = _mailbox.size

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef] = _supervisor
  
  override def toString: String = actor.toString
  
  override def hashCode: Int = actor.hashCode
  
  override def equals(that: Any): Boolean = actor.equals(that)

  private[akka] def supervisor_=(sup: Option[ActorRef]): Unit = _supervisor = sup

  private[akka] def trapExit: List[Class[_ <: Throwable]] = actor.trapExit
  private[akka] def trapExit_=(exits: List[Class[_ <: Throwable]]) = actor.trapExit = exits

  private[akka] def lifeCycle: Option[LifeCycle] = actor.lifeCycle
  private[akka] def lifeCycle_=(cycle: Option[LifeCycle]) = actor.lifeCycle = cycle

  private[akka] def faultHandler: Option[FaultHandlingStrategy] = actor.faultHandler
  private[akka] def faultHandler_=(handler: Option[FaultHandlingStrategy]) = actor.faultHandler = handler

  private def spawnButDoNotStart[T <: Actor: Manifest]: ActorRef = {
    val actor = manifest[T].erasure.asInstanceOf[Class[T]].newInstance
    val actorRef = new ActorRef(() => actor)
    if (!dispatcher.isInstanceOf[ThreadBasedDispatcher]) {
      actorRef.dispatcher = dispatcher
    }
    actorRef
  }

  private[this] def newActor: Actor = {
    val actor = actorFactory match {
      case Left(Some(clazz)) => 
        try { 
          clazz.newInstance 
        } catch { 
          case e: InstantiationException => throw new ActorInitializationException(
            "Could not instantiate Actor due to:\n" + e + 
            "\nMake sure Actor is defined inside a class/trait," + 
            "\nif so put it outside the class/trait, f.e. in a companion object.") 
        }
      case Right(Some(factory)) => 
        factory()
      case _ => 
        throw new ActorInitializationException("Can't create Actor, no Actor class or factory function in scope")
    }
    actor._selfOption = Some(this)
    if (actor eq null) throw new ActorInitializationException("Actor instance passed to ActorRef can not be 'null'")
    actor
  }
  
  private[akka] def restart(reason: Throwable): Unit = {
    Actor.log.info("Restarting actor [%s] configured as PERMANENT.", id)
    val failedActor = actorInstance.get
    failedActor.synchronized {
      Actor.log.debug("Restarting linked actors for actor [%s].", id)
      restartLinkedActors(reason)
      Actor.log.debug("Invoking 'preRestart' for failed actor instance [%s].", id)
      failedActor.preRestart(reason)
      stop
      val freshActor = newActor
      freshActor.synchronized {
        actorInstance.set(freshActor)
        start
        Actor.log.debug("Invoking 'postRestart' for new actor instance [%s].", id)
        freshActor.postRestart(reason)      
      }
      _isKilled = false
    }
  }


  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = {
    joinTransaction(message)

    if (_remoteAddress.isDefined) {
      val requestBuilder = RemoteRequestProtocol.newBuilder
          .setId(RemoteRequestProtocolIdFactory.nextId)
          .setTarget(this.getClass.getName)
          .setTimeout(this.timeout)
          .setUuid(this.uuid)
          .setIsActor(true)
          .setIsOneWay(true)
          .setIsEscaped(false)

      val id = registerSupervisorAsRemoteActor
      if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)

      senderOption.foreach(sender => requestBuilder.setSender(sender.toProtocol))

      RemoteProtocolBuilder.setMessage(message, requestBuilder)
      RemoteClient.clientFor(_remoteAddress.get).send[Any](requestBuilder.build, None)
    } else {
      val invocation = new MessageInvocation(this, message, senderOption.map(Left(_)), transactionSet.get)
      if (messageDispatcher.usesActorMailbox) {
        _mailbox.add(invocation)
        if (_isSuspended) invocation.send
      }
      else invocation.send
    }
  }

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
      message: Any,
      timeout: Long,
      senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = {
    joinTransaction(message)

    if (_remoteAddress.isDefined) {
      val requestBuilder = RemoteRequestProtocol.newBuilder
          .setId(RemoteRequestProtocolIdFactory.nextId)
          .setTarget(this.getClass.getName)
          .setTimeout(this.timeout)
          .setUuid(this.uuid)
          .setIsActor(true)
          .setIsOneWay(false)
          .setIsEscaped(false)
      
      //senderOption.foreach(sender => requestBuilder.setSender(sender.toProtocol))
      RemoteProtocolBuilder.setMessage(message, requestBuilder)
      
      val id = registerSupervisorAsRemoteActor
      if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)
      
      val future = RemoteClient.clientFor(_remoteAddress.get).send(requestBuilder.build, senderFuture)
      if (future.isDefined) future.get
      else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
    } else {
      val future = if (senderFuture.isDefined) senderFuture.get
                   else new DefaultCompletableFuture[T](timeout)
      val invocation = new MessageInvocation(
        this, message, Some(Right(future.asInstanceOf[CompletableFuture[Any]])), transactionSet.get)
      if (messageDispatcher.usesActorMailbox) _mailbox.add(invocation)
      invocation.send
      future
    }
  }

  private def joinTransaction(message: Any) = if (isTransactionSetInScope) {
    // FIXME test to run bench without this trace call
    Actor.log.trace("Joining transaction set [%s];\n\tactor %s\n\twith message [%s]",
                    getTransactionSetInScope, toString, message)
    getTransactionSetInScope.incParties
  }

  /**
   * Callback for the dispatcher. E.g. single entry point to the user code and all protected[this] methods.
   */
  private[akka] def invoke(messageHandle: MessageInvocation) = actor.synchronized {
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
    setTransactionSet(messageHandle.transactionSet)

    val message = messageHandle.message //serializeMessage(messageHandle.message)
    replyTo = messageHandle.replyTo

    try {
      if (actor.base.isDefinedAt(message)) actor.base(message) // invoke user actor's receive partial function
      else throw new IllegalArgumentException("No handler matching message [" + message + "] in " + toString)
    } catch {
      case e =>
        _isKilled = true
        Actor.log.error(e, "Could not invoke actor [%s]", toString)
        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (_supervisor.isDefined) _supervisor.get ! Exit(this, e)
        replyTo match {
          case Some(Right(future)) => future.completeWithException(this, e)
          case _ =>
        }
    } finally {
      clearTransaction
    }
  }

  private def transactionalDispatch[T](messageHandle: MessageInvocation) = {
    var topLevelTransaction = false
    val txSet: Option[CountDownCommitBarrier] =
      if (messageHandle.transactionSet.isDefined) messageHandle.transactionSet
      else {
        topLevelTransaction = true // FIXME create a new internal atomic block that can wait for X seconds if top level tx
        if (isTransactor) {
          Actor.log.trace("Creating a new transaction set (top-level transaction)\n\tfor actor %s\n\twith message %s",
                          toString, messageHandle)
          Some(createNewTransactionSet)
        } else None
      }
    setTransactionSet(txSet)

    val message = messageHandle.message //serializeMessage(messageHandle.message)
    replyTo = messageHandle.replyTo

    def proceed = {
      if (actor.base.isDefinedAt(message)) actor.base(message) // invoke user actor's receive partial function
      else throw new IllegalArgumentException(
        toString + " could not process message [" + message + "]" +
        "\n\tsince no matching 'case' clause in its 'receive' method could be found")
      setTransactionSet(txSet) // restore transaction set to allow atomic block to do commit
    }

    try {
      if (isTransactor) {
        atomic {
          proceed
        }
      } else proceed
    } catch {
      case e: IllegalStateException => {}
      case e =>
        // abort transaction set
        if (isTransactionSetInScope) try {
          getTransactionSetInScope.abort
        } catch { case e: IllegalStateException => {} }
        Actor.log.error(e, "Exception when invoking \n\tactor [%s] \n\twith message [%s]", this, message)

        replyTo match {
          case Some(Right(future)) => future.completeWithException(this, e)
          case _ =>
        }

        clearTransaction
        if (topLevelTransaction) clearTransactionSet

        // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
        if (_supervisor.isDefined) _supervisor.get ! Exit(this, e)
    } finally {
      clearTransaction
      if (topLevelTransaction) clearTransactionSet
    }
  }

  private[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = {
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
    } else _supervisor.foreach(_ ! Exit(dead, reason)) // if 'trapExit' is not defined then pass the Exit on
  }

  private[akka] def restartLinkedActors(reason: Throwable) = {
    linkedActors.toArray.toList.asInstanceOf[List[ActorRef]].foreach { actorRef =>
      if (actorRef.lifeCycle.isEmpty) actorRef.lifeCycle = Some(LifeCycle(Permanent))
      actorRef.lifeCycle.get match {
        case LifeCycle(scope, _) => {
          scope match {
            case Permanent =>
              actorRef.restart(reason)
            case Temporary =>
              Actor.log.info("Actor [%s] configured as TEMPORARY and will not be restarted.", actorRef.id)
              actorRef.stop
              linkedActors.remove(actorRef) // remove the temporary actor
              // if last temporary actor is gone, then unlink me from supervisor
              if (linkedActors.isEmpty) {
                Actor.log.info("All linked actors have died permanently (they were all configured as TEMPORARY)" +
                              "\n\tshutting down and unlinking supervisor actor as well [%s].", 
                              actorRef.id)
                _supervisor.foreach(_ ! UnlinkAndStop(this))
              }
          }
        }
      }
    }
  }

  private[akka] def registerSupervisorAsRemoteActor: Option[String] = synchronized {
    if (_supervisor.isDefined) {
      RemoteClient.clientFor(_remoteAddress.get).registerSupervisorForActor(this)
      Some(_supervisor.get.uuid)
    } else None
  }

  private[akka] def linkedActors: JHashSet[ActorRef] = {
    if (_linkedActors.isEmpty) {
      val set = new JHashSet[ActorRef]
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
        !message.getClass.isAnnotationPresent(Annotations.immutable)) {
      Serializer.Java.deepClone(message)
    } else message
  } else message
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
trait Actor extends Logging {

  /**
   * The 'self' field holds the ActorRef for this actor.
   * <p/>
   * Can be used to send messages to itself:
   * <pre>
   * self ! message
   * </pre>
   * Note: if you are using the 'self' field in the constructor of the Actor
   *       then you have to make the fields/operations that are using it 'lazy'.
   */
   protected[akka] def self: ActorRef = _selfOption.getOrElse(throw new IllegalStateException(
     "ActorRef for instance of Actor [" + getClass.getName + "] is not in scope." + 
     "\n\tAre you using 'self' within the constructor (the class body) of the Actor?" + 
     "\n\tIf so you have to refactor and make all fields that uses the 'self' reference lazy," + 
     "\n\tand move all operations that uses 'self' out of the constructor."))
   
   /** 
    * For internal use only.
    */
  protected[akka] implicit var _selfOption: Option[ActorRef] = None

  /**
   * Holds the hot swapped partial function.
   */
  private var _hotswap: Option[PartialFunction[Any, Unit]] = None // FIXME: _hotswap should be a stack

  // ==================
  // ==== USER API ====
  // ==================

  /**
   * Use to override the default dispatcher.
   */
  def dispatcher_=(dispatcher: MessageDispatcher) = self.dispatcher = dispatcher

  /**
   * User overridable callback/setting.
   * <p/>
   * Identifier for actor, does not have to be a unique one.
   * Default is the class name.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor, identifier for remote actor in RemoteServer etc.
   * But also as the identifier for persistence, which means that you can
   * use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  protected var id: String = this.getClass.getName

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
  protected[akka] var trapExit: List[Class[_ <: Throwable]] = Nil

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
  protected[akka] var faultHandler: Option[FaultHandlingStrategy] = None

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the life-cycle for a supervised actor.
   */
  @volatile var lifeCycle: Option[LifeCycle] = None

  /**
   * User overridable callback/setting.
   * <p/>
   * Optional callback method that is called during initialization.
   * To be implemented by subclassing actor.
   */
  def init {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  def preRestart(reason: Throwable) {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Mandatory callback method that is called during restart and reinitialization after a server crash.
   * To be implemented by subclassing actor.
   */
  def postRestart(reason: Throwable) {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  def initTransactionalState {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Optional callback method that is called during termination.
   * To be implemented by subclassing actor.
   */
  def shutdown {}

  /**
   * User overridable callback/setting.
   * <p/>
   * Set to true if messages should have REQUIRES_NEW semantics, e.g. a new transaction should
   * start if there is no one running, else it joins the existing transaction.
   */
  protected def isTransactor_=(flag: Boolean) = self.isTransactor = flag

  /**
   * User overridable callback/setting.
   * <p/>
   * Partial function implementing the actor logic.
   * To be implemented by subclassing actor.
   * <p/>
   * Example code:
   * <pre>
   *   def receive = {
   *     case Ping =&gt;
   *       println("got a ping")
   *       reply("pong")
   *
   *     case OneWay =&gt;
   *       println("got a oneway")
   *
   *     case _ =&gt;
   *       println("unknown message, ignoring")
   * }
   * </pre>
   */
  protected def receive: PartialFunction[Any, Unit]

  /**
   * Use <code>reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to
   */
  protected[this] def reply(message: Any) = if(!reply_?(message)) throw new IllegalStateException(
    "\n\tNo sender in scope, can't reply. " +
    "\n\tYou have probably used the '!' method to either; " +
    "\n\t\t1. Send a message to a remote actor which does not have a contact address." +
    "\n\t\t2. Send a message from an instance that is *not* an actor" +
    "\n\t\t3. Send a message to an Active Object annotated with the '@oneway' annotation? " +
    "\n\tIf so, switch to '!!' (or remove '@oneway') which passes on an implicit future" +
    "\n\tthat will be bound by the argument passed to 'reply'." +
    "\n\tAlternatively, you can use setReplyToAddress to make sure the actor can be contacted over the network.")

  /**
   * Use <code>reply_?(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  protected[this] def reply_?(message: Any): Boolean = self.replyTo match {
    case Some(Left(actor)) =>
      actor ! message
      true
    case Some(Right(future: Future[Any])) =>
      future completeWithResult message
      true
    case _ =>
      false
  }
  
  /**
   * Starts the actor.
   */
  def start = self.start

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Alias for 'stop'.
   */
  def exit = self.stop

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop = self.stop

  /**
   * Invoking 'makeTransactionRequired' means that the actor will **start** a new transaction if non exists.
   * However, it will always participate in an existing transaction.
   * If transactionality want to be completely turned off then do it by invoking:
   * <pre/>
   *  TransactionManagement.disableTransactions
   * </pre>
   */
  def makeTransactionRequired = self.makeTransactionRequired

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(hostname: String, port: Int): Unit = makeRemote(new InetSocketAddress(hostname, port))

  /**
   * Invoking 'makeRemote' means that an actor will be moved to and invoked on a remote host.
   */
  def makeRemote(address: InetSocketAddress): Unit = self.makeRemote(address)

  /**
   * Set the contact address for this actor. This is used for replying to messages sent 
   * asynchronously when no reply channel exists.
   */
  def setReplyToAddress(hostname: String, port: Int): Unit = self.setReplyToAddress(new InetSocketAddress(hostname, port))

  /**
   * Set the contact address for this actor. This is used for replying to messages sent 
   * asynchronously when no reply channel exists.
   */
  def setReplyToAddress(address: InetSocketAddress): Unit = self.setReplyToAddress(address)
  
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
  protected[this] def link(actorRef: ActorRef) = self.link(actorRef)

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def unlink(actorRef: ActorRef) = self.unlink(actorRef)

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def startLink(actorRef: ActorRef) = self.startLink(actorRef)

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int) = 
    self.startLinkRemote(actorRef, hostname, port)

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawn[T <: Actor : Manifest]: ActorRef = self.spawn[T]

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int): ActorRef = 
    self.spawnRemote[T](hostname, port)

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  protected[this] def spawnLink[T <: Actor: Manifest]: ActorRef = self.spawnLink[T]

  /**
   * Returns the id for the actor.
   */
  def getId = id

  /**
   * Returns the uuid for the actor.
   */
  def uuid = self.uuid

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] def base: PartialFunction[Any, Unit] = lifeCycles orElse (_hotswap getOrElse receive)

  private val lifeCycles: PartialFunction[Any, Unit] = {
    case HotSwap(code) =>        _hotswap = code
    case Restart(reason) =>      self.restart(reason)
    case Exit(dead, reason) =>   self.handleTrapExit(dead, reason)
    case Unlink(child) =>        self.unlink(child)
    case UnlinkAndStop(child) => self.unlink(child); child.stop
    case Kill =>                 throw new ActorKilledException("Actor [" + toString + "] was killed by a Kill message")
  }
  
  override def hashCode(): Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that != null &&
    that.isInstanceOf[Actor] &&
    that.asInstanceOf[Actor].uuid == uuid
  }

  override def toString = "Actor[" + id + ":" + uuid + "]"
}

sealed abstract class DispatcherType
object DispatcherType {
  case object EventBasedThreadPooledProxyInvokingDispatcher extends DispatcherType
  case object EventBasedSingleThreadDispatcher extends DispatcherType
  case object EventBasedThreadPoolDispatcher extends DispatcherType
  case object ThreadBasedDispatcher extends DispatcherType
}

/**
 * For internal use only.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActorMessageInvoker private[akka] (val actorRef: ActorRef) extends MessageInvoker {
  def invoke(handle: MessageInvocation) = actorRef.invoke(handle)
}

/**
 * Remote Actor proxy.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] class RemoteActorRef private (
  uuid: String, className: String, hostname: String, port: Int, timeOut: Long) extends ActorRef {
  val remoteClient = RemoteClient.clientFor(hostname, port)

  override def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = {
    val requestBuilder = RemoteRequestProtocol.newBuilder
        .setId(RemoteRequestProtocolIdFactory.nextId)
        .setTarget(className)
        .setTimeout(timeOut)
        .setUuid(uuid)
        .setIsActor(true)
        .setIsOneWay(true)
        .setIsEscaped(false)
    senderOption.foreach(sender => requestBuilder.setSender(sender.toProtocol))
    RemoteProtocolBuilder.setMessage(message, requestBuilder)
    remoteClient.send[Any](requestBuilder.build, None)
  }

  override def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
      message: Any,
      timeout: Long,
      senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = {
    val requestBuilder = RemoteRequestProtocol.newBuilder
        .setId(RemoteRequestProtocolIdFactory.nextId)
        .setTarget(className)
        .setTimeout(timeout)
        .setUuid(uuid)
        .setIsActor(true)
        .setIsOneWay(false)
        .setIsEscaped(false)
    //senderOption.foreach(sender => requestBuilder.setSender(sender.toProtocol))
    RemoteProtocolBuilder.setMessage(message, requestBuilder)
    val future = remoteClient.send(requestBuilder.build, senderFuture)
    if (future.isDefined) future.get
    else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
  }
}

/**
 * Remote Actor proxy factory.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object RemoteActorRef {
  def apply(uuid: String, className: String, hostname: String, port: Int, timeout: Long): ActorRef =
    (new RemoteActorRef(uuid, className, hostname, port, timeout)).start
}

