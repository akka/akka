/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.dispatch._
import akka.config.Config._
import akka.config.Supervision._
import akka.AkkaException
import akka.util._
import ReflectiveAccess._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ ScheduledFuture, ConcurrentHashMap, TimeUnit }
import java.util.{ Map => JMap }
import java.lang.reflect.Field

import scala.reflect.BeanProperty
import scala.collection.immutable.Stack
import scala.annotation.tailrec

private[akka] object ActorRefInternals extends Logging {

  /**
   * LifeCycles for ActorRefs.
   */
  private[akka] sealed trait StatusType
  object UNSTARTED extends StatusType
  object RUNNING extends StatusType
  object BEING_RESTARTED extends StatusType
  object SHUTDOWN extends StatusType
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
trait ActorRef extends ActorRefShared with java.lang.Comparable[ActorRef] { scalaRef: ScalaActorRef =>
  //Reuse same logger
  import Actor.log

  // Only mutable for RemoteServer in order to maintain identity across nodes
  @volatile
  protected[akka] var _uuid = newUuid
  @volatile
  protected[this] var _status: ActorRefInternals.StatusType = ActorRefInternals.UNSTARTED
  @volatile
  protected[akka] var _futureTimeout: Option[ScheduledFuture[AnyRef]] = None
  protected[akka] val guard = new ReentrantGuard

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
  @BeanProperty
  @volatile
  var id: String = _uuid.toString

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  @BeanProperty
  @volatile
  var timeout: Long = Actor.TIMEOUT

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  @volatile
  var receiveTimeout: Option[Long] = None

  /**
   * Akka Java API
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  def setReceiveTimeout(timeout: Long) = this.receiveTimeout = Some(timeout)
  def getReceiveTimeout(): Option[Long] = receiveTimeout

  /**
   * Akka Java API
   *  A faultHandler defines what should be done when a linked actor signals an error.
   * <p/>
   * Can be one of:
   * <pre>
   * getContext().setFaultHandler(new AllForOneStrategy(new Class[]{Throwable.class},maxNrOfRetries, withinTimeRange));
   * </pre>
   * Or:
   * <pre>
   * getContext().setFaultHandler(new OneForOneStrategy(new Class[]{Throwable.class},maxNrOfRetries, withinTimeRange));
   * </pre>
   */
  def setFaultHandler(handler: FaultHandlingStrategy)
  def getFaultHandler(): FaultHandlingStrategy


  /**
   * Akka Java API
   *  A lifeCycle defines whether the actor will be stopped on error (Temporary) or if it can be restarted (Permanent)
   * <p/>
   * Can be one of:
   *
   * import static akka.config.Supervision.*;
   * <pre>
   * getContext().setLifeCycle(permanent());
   * </pre>
   * Or:
   * <pre>
   * getContext().setLifeCycle(temporary());
   * </pre>
   */
  def setLifeCycle(lifeCycle: LifeCycle): Unit
  def getLifeCycle(): LifeCycle

  /**
   * Akka Java API
   * The default dispatcher is the <tt>Dispatchers.globalExecutorBasedEventDrivenDispatcher</tt>.
   * This means that all actors will share the same event-driven executor based dispatcher.
   * <p/>
   * You can override it so it fits the specific use-case that the actor is used for.
   * See the <tt>akka.dispatch.Dispatchers</tt> class for the different
   * dispatchers available.
   * <p/>
   * The default is also that all actors that are created and spawned from within this actor
   * is sharing the same dispatcher as its creator.
   */
  def setDispatcher(dispatcher: MessageDispatcher) = this.dispatcher = dispatcher
  def getDispatcher(): MessageDispatcher = dispatcher

  /**
   * Returns on which node this actor lives if None it lives in the local ActorRegistry
   */
  def homeAddress: Option[InetSocketAddress]

  /**
   * Java API
   */
  def getHomeAddress(): InetSocketAddress = homeAddress getOrElse null

  /**
   *   Holds the hot swapped partial function.
   */
  @volatile
  protected[akka] var hotswap = Stack[PartialFunction[Any, Unit]]()

  /**
   *  This is a reference to the message currently being processed by the actor
   */
  @volatile
  protected[akka] var currentMessage: MessageInvocation = null

  /**
   * Comparison only takes uuid into account.
   */
  def compareTo(other: ActorRef) = this.uuid compareTo other.uuid

  /**
   * Returns the uuid for the actor.
   */
  def getUuid() = _uuid
  def uuid = _uuid

  /**
   * Akka Java API
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def getSender(): Option[ActorRef] = sender

  /**
   * Akka Java API
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '!!' or '!!!', else None.
   */
  def getSenderFuture(): Option[CompletableFuture[Any]] = senderFuture

  /**
   * Is the actor being restarted?
   */
  def isBeingRestarted: Boolean = _status == ActorRefInternals.BEING_RESTARTED

  /**
   * Is the actor running?
   */
  def isRunning: Boolean = _status match {
    case ActorRefInternals.BEING_RESTARTED | ActorRefInternals.RUNNING => true
    case _ => false
  }

  /**
   * Is the actor shut down?
   */
  def isShutdown: Boolean = _status == ActorRefInternals.SHUTDOWN

  /**
   * Is the actor ever started?
   */
  def isUnstarted: Boolean = _status == ActorRefInternals.UNSTARTED

  /**
   * Is the actor able to handle the message passed in as arguments?
   */
  def isDefinedAt(message: Any): Boolean = actor.isDefinedAt(message)

  /**
   * Only for internal use. UUID is effectively final.
   */
  protected[akka] def uuid_=(uid: Uuid) = _uuid = uid

  /**
   * Akka Java API
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   * <pre>
   * actor.sendOneWay(message);
   * </pre>
   * <p/>
   */
  def sendOneWay(message: AnyRef): Unit = sendOneWay(message, null)

  /**
   * Akka Java API
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   * Allows you to pass along the sender of the messag.
   * <p/>
   * <pre>
   * actor.sendOneWay(message, context);
   * </pre>
   * <p/>
   */
  def sendOneWay(message: AnyRef, sender: ActorRef): Unit = this.!(message)(Option(sender))

  /**
   * Akka Java API
   * @see sendRequestReply(message: AnyRef, timeout: Long, sender: ActorRef)
   * Uses the defualt timeout of the Actor (setTimeout()) and omits the sender reference
   */
  def sendRequestReply(message: AnyRef): AnyRef = sendRequestReply(message, timeout, null)

  /**
   * Akka Java API
   * @see sendRequestReply(message: AnyRef, timeout: Long, sender: ActorRef)
   * Uses the defualt timeout of the Actor (setTimeout())
   */
  def sendRequestReply(message: AnyRef, sender: ActorRef): AnyRef = sendRequestReply(message, timeout, sender)

  /**
   * Akka Java API
   * Sends a message asynchronously and waits on a future for a reply message under the hood.
   * <p/>
   * It waits on the reply either until it receives it or until the timeout expires
   * (which will throw an ActorTimeoutException). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReply</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReply(message: AnyRef, timeout: Long, sender: ActorRef): AnyRef = {
    !!(message, timeout)(Option(sender)).getOrElse(throw new ActorTimeoutException(
      "Message [" + message +
      "]\n\tsent to [" + actorClassName +
      "]\n\tfrom [" + (if (sender ne null) sender.actorClassName else "nowhere") +
      "]\n\twith timeout [" + timeout +
      "]\n\ttimed out."))
      .asInstanceOf[AnyRef]
  }

  /**
   * Akka Java API
   * @see sendRequestReplyFuture(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout()) and omits the sender
   */
  def sendRequestReplyFuture(message: AnyRef): Future[_] = sendRequestReplyFuture(message, timeout, null)

  /**
   * Akka Java API
   * @see sendRequestReplyFuture(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout())
   */
  def sendRequestReplyFuture(message: AnyRef, sender: ActorRef): Future[_] = sendRequestReplyFuture(message, timeout, sender)

  /**
   *  Akka Java API
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReplyFuture</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReplyFuture(message: AnyRef, timeout: Long, sender: ActorRef): Future[_] = !!!(message, timeout)(Option(sender))

  /**
   * Akka Java API
   * Forwards the message specified to this actor and preserves the original sender of the message
   */
  def forward(message: AnyRef, sender: ActorRef): Unit =
    if (sender eq null) throw new IllegalArgumentException("The 'sender' argument to 'forward' can't be null")
    else forward(message)(Some(sender))

  /**
   * Akka Java API
   * Use <code>getContext().replyUnsafe(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def replyUnsafe(message: AnyRef) = reply(message)

  /**
   * Akka Java API
   * Use <code>getContext().replySafe(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  def replySafe(message: AnyRef): Boolean = reply_?(message)

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def actorClass: Class[_ <: Actor]

  /**
   * Akka Java API
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def getActorClass(): Class[_ <: Actor] = actorClass

  /**
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  def actorClassName: String

  /**
   * Akka Java API
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  def getActorClassName(): String = actorClassName

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher

  /**
   * Starts up the actor and its message queue.
   */
  def start(): ActorRef

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Alias for 'stop'.
   */
  def exit() = stop()

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop(): Unit

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field of the 'faultHandler' has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   */
  def link(actorRef: ActorRef): Unit

  /**
   * Unlink the actor.
   */
  def unlink(actorRef: ActorRef): Unit

  /**
   * Atomically start and link an actor.
   */
  def startLink(actorRef: ActorRef): Unit

  /**
   * Atomically start, link and make an actor remote.
   */
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawn(clazz: Class[_ <: Actor]): ActorRef

  /**
   * Atomically create (from actor class), make it remote and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef

  /**
   * Atomically create (from actor class), link and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef

  /**
   * Atomically create (from actor class), make it remote, link and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef

  /**
   * Returns the mailbox size.
   */
  def mailboxSize = dispatcher.mailboxSize(this)

  /**
   * Akka Java API
   * Returns the mailbox size.
   */
  def getMailboxSize(): Int = mailboxSize

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef]

  /**
   * Akka Java API
   * Returns the supervisor, if there is one.
   */
  def getSupervisor(): ActorRef = supervisor getOrElse null

  protected[akka] def invoke(messageHandle: MessageInvocation): Unit

  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T]

  protected[akka] def actorInstance: AtomicReference[Actor]

  protected[akka] def actor: Actor = actorInstance.get

  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit

  protected[akka] def mailbox: AnyRef
  protected[akka] def mailbox_=(value: AnyRef): AnyRef

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit

  protected[akka] def registerSupervisorAsRemoteActor: Option[Uuid]

  protected[akka] def linkedActors: JMap[Uuid, ActorRef]

  override def hashCode: Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[ActorRef] &&
    that.asInstanceOf[ActorRef].uuid == uuid
  }

  override def toString = "Actor[" + id + ":" + uuid + "]"

  protected[akka] def checkReceiveTimeout = {
    cancelReceiveTimeout
    if (receiveTimeout.isDefined && dispatcher.mailboxSize(this) <= 0) { //Only reschedule if desired and there are currently no more messages to be processed
      log.slf4j.debug("Scheduling timeout for {}", this)
      _futureTimeout = Some(Scheduler.scheduleOnce(this, ReceiveTimeout, receiveTimeout.get, TimeUnit.MILLISECONDS))
    }
  }

  protected[akka] def cancelReceiveTimeout = {
    if (_futureTimeout.isDefined) {
      _futureTimeout.get.cancel(true)
      _futureTimeout = None
      log.slf4j.debug("Timeout canceled for {}", this)
    }
  }
}

/**
 * Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalActorRef private[akka] (
  private[this] val actorFactory: () => Actor,
  val homeAddress: Option[InetSocketAddress],
  val clientManaged: Boolean = false)
  extends ActorRef with ScalaActorRef {

  @volatile
  private[akka] lazy val _linkedActors = new ConcurrentHashMap[Uuid, ActorRef]
  @volatile
  private[akka] var _supervisor: Option[ActorRef] = None
  @volatile
  private var maxNrOfRetriesCount: Int = 0
  @volatile
  private var restartsWithinTimeRangeTimestamp: Long = 0L
  @volatile
  private var _mailbox: AnyRef = _
  @volatile
  private[akka] var _dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher

  protected[akka] val actorInstance = guard.withGuard { new AtomicReference[Actor](newActor) }

  //If it was started inside "newActor", initialize it
  if (isRunning) initializeActorInstance

  // used only for deserialization
  private[akka] def this(
    __uuid: Uuid,
    __id: String,
    __timeout: Long,
    __receiveTimeout: Option[Long],
    __lifeCycle: LifeCycle,
    __supervisor: Option[ActorRef],
    __hotswap: Stack[PartialFunction[Any, Unit]],
    __factory: () => Actor,
    __homeAddress: Option[InetSocketAddress]) = {
    this(__factory, __homeAddress)
    _uuid = __uuid
    id = __id
    timeout = __timeout
    receiveTimeout = __receiveTimeout
    lifeCycle = __lifeCycle
    _supervisor = __supervisor
    hotswap = __hotswap
    setActorSelfFields(actor,this)
    start
  }

  /**
   * Returns whether this actor ref is client-managed remote or not
   */
  private[akka] final def isClientManaged_? = clientManaged && homeAddress.isDefined && isRemotingEnabled

  // ========= PUBLIC FUNCTIONS =========

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  def actorClass: Class[_ <: Actor] = actor.getClass.asInstanceOf[Class[_ <: Actor]]

  /**
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  def actorClassName: String = actorClass.getName

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit = guard.withGuard {
    if (!isBeingRestarted) {
      if (!isRunning) _dispatcher = md
      else throw new ActorInitializationException(
        "Can not swap dispatcher for " + toString + " after it has been started")
    }
  }

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher = _dispatcher

  /**
   * Starts up the actor and its message queue.
   */
  def start: ActorRef = guard.withGuard {
    if (isShutdown) throw new ActorStartException(
      "Can't restart an actor that has been shut down with 'stop' or 'exit'")
    if (!isRunning) {
      dispatcher.attach(this)

      _status = ActorRefInternals.RUNNING

      // If we are not currently creating this ActorRef instance
      if ((actorInstance ne null) && (actorInstance.get ne null))
        initializeActorInstance

      if (isClientManaged_?)
        Actor.remote.registerClientManagedActor(homeAddress.get.getHostName, homeAddress.get.getPort, uuid)

      checkReceiveTimeout //Schedule the initial Receive timeout
    }
    this
  }

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop() = guard.withGuard {
    if (isRunning) {
      receiveTimeout = None
      cancelReceiveTimeout
      dispatcher.detach(this)
      _status = ActorRefInternals.SHUTDOWN
      actor.postStop
      Actor.registry.unregister(this)
      if (isRemotingEnabled) {
        if (isClientManaged_?)
          Actor.remote.unregisterClientManagedActor(homeAddress.get.getHostName, homeAddress.get.getPort, uuid)
        Actor.remote.unregister(this)
      }
      setActorSelfFields(actorInstance.get,null)
    } //else if (isBeingRestarted) throw new ActorKilledException("Actor [" + toString + "] is being restarted.")
  }

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field of the 'faultHandler' has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def link(actorRef: ActorRef) = guard.withGuard {
    if (actorRef.supervisor.isDefined) throw new IllegalActorStateException(
      "Actor can only have one supervisor [" + actorRef + "], e.g. link(actor) fails")
    linkedActors.put(actorRef.uuid, actorRef)
    actorRef.supervisor = Some(this)
    Actor.log.slf4j.debug("Linking actor [{}] to actor [{}]", actorRef, this)
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def unlink(actorRef: ActorRef) = guard.withGuard {
    if (!linkedActors.containsKey(actorRef.uuid)) throw new IllegalActorStateException(
      "Actor [" + actorRef + "] is not a linked actor, can't unlink")
    linkedActors.remove(actorRef.uuid)
    actorRef.supervisor = None
    Actor.log.slf4j.debug("Unlinking actor [{}] from actor [{}]", actorRef, this)
  }

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLink(actorRef: ActorRef): Unit = guard.withGuard {
    link(actorRef)
    actorRef.start
  }

  /**
   * Atomically start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit = {
    //TODO: REVISIT: REMOVED
  }

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawn(clazz: Class[_ <: Actor]): ActorRef = guard.withGuard {
    Actor.actorOf(clazz).start
  }

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long = Actor.TIMEOUT): ActorRef = guard.withGuard {
    ensureRemotingEnabled
    val ref = Actor.remote.actorOf(clazz, hostname, port)
    ref.timeout = timeout
    ref.start
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef = guard.withGuard {
    val actor = Actor.actorOf(clazz)
    link(actor)
    actor.start
    actor
  }

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long = Actor.TIMEOUT): ActorRef =
    guard.withGuard {
      ensureRemotingEnabled
      val actor = Actor.remote.actorOf(clazz, hostname, port)
      actor.timeout = timeout
      link(actor)
      actor.start
      actor
    }

  /**
   * Returns the mailbox.
   */
  def mailbox: AnyRef = _mailbox

  protected[akka] def mailbox_=(value: AnyRef): AnyRef = { _mailbox = value; value }

  /**
   * Shuts down and removes all linked actors.
   */
  def shutdownLinkedActors() {
    val i = linkedActors.values.iterator
    while(i.hasNext) {
      i.next.stop
      i.remove
    }
  }

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef] = _supervisor

  // ========= AKKA PROTECTED FUNCTIONS =========

  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = _supervisor = sup

  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit =
    if (isClientManaged_?) {
      Actor.remote.send[Any](
        message, senderOption, None, homeAddress.get, timeout, true, this, None, ActorType.ScalaActor, None)
    } else
      dispatcher dispatchMessage new MessageInvocation(this, message, senderOption, None)

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = {
      if (isClientManaged_?) {
      val future = Actor.remote.send[T](
        message, senderOption, senderFuture, homeAddress.get, timeout, false, this, None, ActorType.ScalaActor, None)
      if (future.isDefined) future.get
      else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
    } else {
      val future = if (senderFuture.isDefined) senderFuture else Some(new DefaultCompletableFuture[T](timeout))
      dispatcher dispatchMessage new MessageInvocation(this, message, senderOption, future.asInstanceOf[Some[CompletableFuture[Any]]])
      future.get
    }
  }

  /**
   * Callback for the dispatcher. This is the single entry point to the user Actor implementation.
   */
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = guard.withGuard {
    if (isShutdown) Actor.log.slf4j.warn("Actor [{}] is shut down,\n\tignoring message [{}]", toString, messageHandle)
    else {
      currentMessage = messageHandle
      try {
        Actor.log.slf4j.trace("Invoking actor with message: {}\n", messageHandle)
        try {
          cancelReceiveTimeout // FIXME: leave this here?
          actor(messageHandle.message)
        } catch {
          case e: InterruptedException => {} // received message while actor is shutting down, ignore
          case e => handleExceptionInDispatch(e, messageHandle.message)
        } finally {
          checkReceiveTimeout // Reschedule receive timeout
        }
      } catch {
        case e =>
          Actor.log.slf4j.error("Could not invoke actor [{}]", this)
          Actor.log.slf4j.error("Problem", e)
          throw e
      } finally {
        currentMessage = null //TODO: Don't reset this, we might want to resend the message
      }
    }
  }

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable) {
    faultHandler match {
      case AllForOneStrategy(trapExit,maxRetries, within) if trapExit.exists(_.isAssignableFrom(reason.getClass)) =>
        restartLinkedActors(reason, maxRetries, within)

      case OneForOneStrategy(trapExit,maxRetries, within) if trapExit.exists(_.isAssignableFrom(reason.getClass)) =>
        dead.restart(reason, maxRetries, within)

      case _ =>
        if(_supervisor.isDefined)
          notifySupervisorWithMessage(Exit(this, reason))
        else
          dead.stop
    }
  }

  private def requestRestartPermission(maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Boolean = {
    val denied = if (maxNrOfRetries.isEmpty && withinTimeRange.isEmpty) {  //Immortal
      false
    } else if (withinTimeRange.isEmpty) { // restrict number of restarts
      maxNrOfRetriesCount += 1 //Increment number of retries
      maxNrOfRetriesCount > maxNrOfRetries.get
    } else {  // cannot restart more than N within M timerange
      maxNrOfRetriesCount += 1 //Increment number of retries
      val windowStart = restartsWithinTimeRangeTimestamp
      val now         = System.currentTimeMillis
      val retries     = maxNrOfRetriesCount
      //We are within the time window if it isn't the first restart, or if the window hasn't closed
      val insideWindow   = if (windowStart == 0) false
                          else (now - windowStart) <= withinTimeRange.get

      //The actor is dead if it dies X times within the window of restart
      val unrestartable = insideWindow && retries > maxNrOfRetries.getOrElse(1)

      if (windowStart == 0 || !insideWindow) //(Re-)set the start of the window
        restartsWithinTimeRangeTimestamp = now

      if (windowStart != 0 && !insideWindow) //Reset number of restarts if window has expired
        maxNrOfRetriesCount = 1

      unrestartable
    }

    denied == false //If we weren't denied, we have a go
  }

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
     def performRestart {
      Actor.log.slf4j.info("Restarting actor [{}] configured as PERMANENT.", id)
      val failedActor = actorInstance.get

      failedActor match {
        case p: Proxyable =>
          //p.swapProxiedActor(freshActor) //TODO: broken
          Actor.log.slf4j.debug("Invoking 'preRestart' for failed actor instance [{}].", id)
          failedActor.preRestart(reason)
          Actor.log.slf4j.debug("Invoking 'postRestart' for failed actor instance [{}].", id)
          failedActor.postRestart(reason)
        case _ =>
          Actor.log.slf4j.debug("Invoking 'preRestart' for failed actor instance [{}].", id)
          failedActor.preRestart(reason)
          val freshActor = newActor
          setActorSelfFields(failedActor,null) //Only null out the references if we could instantiate the new actor
          actorInstance.set(freshActor) //Assign it here so if preStart fails, we can null out the sef-refs next call
          freshActor.preStart
          Actor.log.slf4j.debug("Invoking 'postRestart' for new actor instance [{}].", id)
          freshActor.postRestart(reason)
      }
    }

    def tooManyRestarts {
      Actor.log.slf4j.warn(
        "Maximum number of restarts [{}] within time range [{}] reached." +
        "\n\tWill *not* restart actor [{}] anymore." +
        "\n\tLast exception causing restart was" +
        "\n\t[{}].",
        Array[AnyRef](maxNrOfRetries, withinTimeRange, this, reason))
      _supervisor.foreach { sup =>
        // can supervisor handle the notification?
        val notification = MaximumNumberOfRestartsWithinTimeRangeReached(this, maxNrOfRetries, withinTimeRange, reason)
        if (sup.isDefinedAt(notification)) notifySupervisorWithMessage(notification)
        else Actor.log.slf4j.warn(
          "No message handler defined for system message [MaximumNumberOfRestartsWithinTimeRangeReached]" +
          "\n\tCan't send the message to the supervisor [{}].", sup)
      }

      stop
    }

    @tailrec def attemptRestart() {
      val success = if (requestRestartPermission(maxNrOfRetries,withinTimeRange)) {
        guard.withGuard[Boolean] {
          _status = ActorRefInternals.BEING_RESTARTED

          lifeCycle match {
            case Temporary =>
             shutDownTemporaryActor(this)
             true
            case _ => // either permanent or none where default is permanent
              val success = try {
                performRestart
                true
              } catch {
                case e => Actor.log.slf4j.debug("Unexpected exception during restart",e)
                          false //An error or exception here should trigger a retry
              }

              Actor.log.slf4j.debug("Restart: {} for [{}].", success, id)

              if (success) {
                _status = ActorRefInternals.RUNNING
                dispatcher.resume(this)
                restartLinkedActors(reason,maxNrOfRetries,withinTimeRange)
              }

              success
          }
        }
      } else {
        tooManyRestarts
        true //Done
      }

      if (success)
        () //Alles gut
      else
        attemptRestart
    }

    attemptRestart() //Tailrecursion
  }

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) = {
    import scala.collection.JavaConversions._
    linkedActors.values foreach { actorRef =>
      actorRef.lifeCycle match {
        // either permanent or none where default is permanent
        case Temporary => shutDownTemporaryActor(actorRef)
        case _ => actorRef.restart(reason, maxNrOfRetries, withinTimeRange)
      }
    }
  }

  protected[akka] def registerSupervisorAsRemoteActor: Option[Uuid] = guard.withGuard {
    ensureRemotingEnabled
    if (_supervisor.isDefined) {
      if (homeAddress.isDefined)
        Actor.remote.registerSupervisorForActor(this)
      Some(_supervisor.get.uuid)
    } else None
  }

  protected[akka] def linkedActors: JMap[Uuid, ActorRef] = _linkedActors

  // ========= PRIVATE FUNCTIONS =========

  private[this] def newActor: Actor = {
    val a = Actor.actorRefInCreation.withValue(Some(this)) { actorFactory() }
    if (a eq null) throw new ActorInitializationException("Actor instance passed to ActorRef can not be 'null'")
    a
  }

  private def shutDownTemporaryActor(temporaryActor: ActorRef) {
    Actor.log.slf4j.info("Actor [{}] configured as TEMPORARY and will not be restarted.", temporaryActor.id)
    temporaryActor.stop
    linkedActors.remove(temporaryActor.uuid) // remove the temporary actor
    // if last temporary actor is gone, then unlink me from supervisor
    if (linkedActors.isEmpty) {
      Actor.log.slf4j.info(
        "All linked actors have died permanently (they were all configured as TEMPORARY)" +
        "\n\tshutting down and unlinking supervisor actor as well [{}].",
        temporaryActor.id)
      notifySupervisorWithMessage(UnlinkAndStop(this))
    }

    true
  }

  private def handleExceptionInDispatch(reason: Throwable, message: Any) = {
    Actor.log.slf4j.error("Exception when invoking \n\tactor [{}] \n\twith message [{}]", this, message)
    Actor.log.slf4j.error("Problem", reason)

    //Prevent any further messages to be processed until the actor has been restarted
    dispatcher.suspend(this)

    senderFuture.foreach(_.completeWithException(reason))

    if (supervisor.isDefined) notifySupervisorWithMessage(Exit(this, reason))
    else {
      lifeCycle match {
        case Temporary => shutDownTemporaryActor(this)
        case _ => dispatcher.resume(this) //Resume processing for this actor
      }
    }
  }

  private def notifySupervisorWithMessage(notification: LifeCycleMessage) = {
    // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
    _supervisor.foreach { sup =>
      if (sup.isShutdown) { // if supervisor is shut down, game over for all linked actors
        shutdownLinkedActors
        stop
      } else sup ! notification // else notify supervisor
    }
  }

  private def setActorSelfFields(actor: Actor, value: ActorRef) {

    @tailrec def lookupAndSetSelfFields(clazz: Class[_],actor: Actor, value: ActorRef): Boolean = {
      val success = try {
        val selfField = clazz.getDeclaredField("self")
        val someSelfField = clazz.getDeclaredField("someSelf")
        selfField.setAccessible(true)
        someSelfField.setAccessible(true)
        selfField.set(actor,value)
        someSelfField.set(actor, if (value ne null) Some(value) else null)
        true
      } catch {
        case e: NoSuchFieldException => false
      }

      if (success) {
        true
      }
      else {
        val parent = clazz.getSuperclass
        if (parent eq null)
          throw new IllegalActorStateException(toString + " is not an Actor since it have not mixed in the 'Actor' trait")
        lookupAndSetSelfFields(parent,actor,value)
      }
    }

    lookupAndSetSelfFields(actor.getClass,actor,value)
  }

  private def initializeActorInstance = {
    actor.preStart // run actor preStart
    Actor.log.slf4j.trace("[{}] has started", toString)
    Actor.registry.register(this)
  }
}

/**
 * System messages for RemoteActorRef.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteActorSystemMessage {
  val Stop = "RemoteActorRef:stop".intern
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] case class RemoteActorRef private[akka] (
  classOrServiceName: String,
  val actorClassName: String,
  val hostname: String,
  val port: Int,
  _timeout: Long,
  loader: Option[ClassLoader],
  val actorType: ActorType = ActorType.ScalaActor)
  extends ActorRef with ScalaActorRef {

  ensureRemotingEnabled

  val homeAddress = Some(new InetSocketAddress(hostname, port))

  //protected def clientManaged = classOrServiceName.isEmpty //If no class or service name, it's client managed
  id = classOrServiceName
  //id = classOrServiceName.getOrElse("uuid:" + uuid) //If we're a server-managed we want to have classOrServiceName as id, or else, we're a client-managed and we want to have our uuid as id

  timeout = _timeout

  start

  def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit =
    Actor.remote.send[Any](message, senderOption, None, homeAddress.get, timeout, true, this, None, actorType, loader)

  def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = {
    val future = Actor.remote.send[T](message, senderOption, senderFuture, homeAddress.get, timeout, false, this, None, actorType, loader)
    if (future.isDefined) future.get
    else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
  }

  def start: ActorRef = synchronized {
    _status = ActorRefInternals.RUNNING
    //if (clientManaged)
    //  Actor.remote.registerClientManagedActor(homeAddress.getHostName,homeAddress.getPort, uuid)
    this
  }

  def stop: Unit = synchronized {
    if (_status == ActorRefInternals.RUNNING) {
      _status = ActorRefInternals.SHUTDOWN
      postMessageToMailbox(RemoteActorSystemMessage.Stop, None)
     // if (clientManaged)
     //   Actor.remote.unregisterClientManagedActor(homeAddress.getHostName,homeAddress.getPort, uuid)
    }
  }

  protected[akka] def registerSupervisorAsRemoteActor: Option[Uuid] = None

  // ==== NOT SUPPORTED ====
  def actorClass: Class[_ <: Actor] = unsupported
  def dispatcher_=(md: MessageDispatcher): Unit = unsupported
  def dispatcher: MessageDispatcher = unsupported
  def link(actorRef: ActorRef): Unit = unsupported
  def unlink(actorRef: ActorRef): Unit = unsupported
  def startLink(actorRef: ActorRef): Unit = unsupported
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit = unsupported
  def spawn(clazz: Class[_ <: Actor]): ActorRef = unsupported
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef = unsupported
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef = unsupported
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef = unsupported
  def supervisor: Option[ActorRef] = unsupported
  def shutdownLinkedActors: Unit = unsupported
  protected[akka] def mailbox: AnyRef = unsupported
  protected[akka] def mailbox_=(value: AnyRef): AnyRef = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = unsupported
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = unsupported
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = unsupported
  protected[akka] def linkedActors: JMap[Uuid, ActorRef] = unsupported
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = unsupported
  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = unsupported
  protected[akka] def actorInstance: AtomicReference[Actor] = unsupported
  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
}

/**
 * This trait represents the common (external) methods for all ActorRefs
 * Needed because implicit conversions aren't applied when instance imports are used
 *
 * i.e.
 * var self: ScalaActorRef = ...
 * import self._
 * //can't call ActorRef methods here unless they are declared in a common
 * //superclass, which ActorRefShared is.
 */
trait ActorRefShared {
  /**
   * Returns the uuid for the actor.
   */
  def uuid: Uuid

  /**
   * Shuts down and removes all linked actors.
   */
  def shutdownLinkedActors(): Unit
}

/**
 * This trait represents the Scala Actor API
 * There are implicit conversions in ../actor/Implicits.scala
 * from ActorRef -> ScalaActorRef and back
 */
trait ScalaActorRef extends ActorRefShared { ref: ActorRef =>

  /**
   * Identifier for actor, does not have to be a unique one. Default is the 'uuid'.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor(id), identifier for remote
   * actor in RemoteServer etc.But also as the identifier for persistence, which means
   * that you can use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  def id: String

  def id_=(id: String): Unit /**
   * User overridable callback/setting.
   * <p/>
   * Defines the life-cycle for a supervised actor.
   */
  @volatile
  @BeanProperty
  var lifeCycle: LifeCycle = UndefinedLifeCycle

  /**
   * User overridable callback/setting.
   * <p/>
   *  Don't forget to supply a List of exception types to intercept (trapExit)
   * <p/>
   * Can be one of:
   * <pre>
   *  faultHandler = AllForOneStrategy(trapExit = List(classOf[Exception]),maxNrOfRetries, withinTimeRange)
   * </pre>
   * Or:
   * <pre>
   *  faultHandler = OneForOneStrategy(trapExit = List(classOf[Exception]),maxNrOfRetries, withinTimeRange)
   * </pre>
   */
  @volatile
  @BeanProperty
  var faultHandler: FaultHandlingStrategy = NoFaultHandlingStrategy

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def sender: Option[ActorRef] = {
    val msg = currentMessage
    if (msg eq null) None
    else msg.sender
  }

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '!!' or '!!!', else None.
   */
  def senderFuture(): Option[CompletableFuture[Any]] = {
    val msg = currentMessage
    if (msg eq null) None
    else msg.senderFuture
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
  def !(message: Any)(implicit sender: Option[ActorRef] = None): Unit = {
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
  def !!(message: Any, timeout: Long = this.timeout)(implicit sender: Option[ActorRef] = None): Option[Any] = {
    if (isRunning) {
      val future = postMessageToMailboxAndCreateFutureResultWithTimeout[Any](message, timeout, sender, None)
      val isMessageJoinPoint = if (isTypedActorEnabled) TypedActorModule.resolveFutureIfMessageIsJoinPoint(message, future)
      else false
      try {
        future.await
      } catch {
        case e: FutureTimeoutException =>
          if (isMessageJoinPoint) throw e
          else None
      }
      if (future.exception.isDefined) throw future.exception.get
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
  def !!![T](message: Any, timeout: Long = this.timeout)(implicit sender: Option[ActorRef] = None): Future[T] = {
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
      if (sender.get.senderFuture.isDefined)
        postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, sender.get.sender, sender.get.senderFuture)
      else
        postMessageToMailbox(message, sender.get.sender)
    } else throw new ActorInitializationException("Actor has not been started, you need to invoke 'actor.start' before using it")
  }

  /**
   * Use <code>self.reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def reply(message: Any) = if (!reply_?(message)) throw new IllegalActorStateException(
    "\n\tNo sender in scope, can't reply. " +
    "\n\tYou have probably: " +
    "\n\t\t1. Sent a message to an Actor from an instance that is NOT an Actor." +
    "\n\t\t2. Invoked a method on an TypedActor from an instance NOT an TypedActor." +
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
      //TODO: optimize away this allocation, perhaps by having implicit self: Option[ActorRef] in signature
      sender.get.!(message)(Some(this))
      true
    } else false
  }

  /**
   * Abstraction for unification of sender and senderFuture for later reply
   */
  def channel: Channel[Any] = {
    if (senderFuture.isDefined) {
      new Channel[Any] {
        val future = senderFuture.get
        def !(msg: Any) = future completeWithResult msg
      }
    } else if (sender.isDefined) {
      val someSelf = Some(this)
      new Channel[Any] {
        val client = sender.get
        def !(msg: Any) = client.!(msg)(someSelf)
      }
    } else throw new IllegalActorStateException("No channel available")
  }

  /**
   * Atomically create (from actor class) and start an actor.
   */
  def spawn[T <: Actor: Manifest]: ActorRef =
    spawn(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Atomically create (from actor class), start and make an actor remote.
   */
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int, timeout: Long): ActorRef = {
    ensureRemotingEnabled
    spawnRemote(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]], hostname, port, timeout)
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   */
  def spawnLink[T <: Actor: Manifest]: ActorRef =
    spawnLink(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   */
  def spawnLinkRemote[T <: Actor: Manifest](hostname: String, port: Int, timeout: Long): ActorRef = {
    ensureRemotingEnabled
    spawnLinkRemote(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]], hostname, port, timeout)
  }
}

/**
 * Abstraction for unification of sender and senderFuture for later reply
 */
abstract class Channel[T] {
  /**
   * Sends the specified message to the channel
   * Scala API
   */
  def !(msg: T): Unit

  /**
   * Sends the specified message to the channel
   * Java API
   */
  def sendOneWay(msg: T): Unit = this.!(msg)
}
