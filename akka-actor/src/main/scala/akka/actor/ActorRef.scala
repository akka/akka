/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.event.EventHandler
import akka.dispatch._
import akka.config.Config
import akka.config.Supervision._
import akka.util._
import ReflectiveAccess._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ScheduledFuture, ConcurrentHashMap, TimeUnit }
import java.util.{ Map ⇒ JMap }

import scala.reflect.BeanProperty
import scala.collection.immutable.Stack
import scala.annotation.tailrec
import java.lang.IllegalStateException

private[akka] object ActorRefInternals {

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
 * Abstraction for unification of sender and senderFuture for later reply.
 * Can be stored away and used at a later point in time.
 */
abstract class Channel[T] {

  /**
   * Scala API. <p/>
   * Sends the specified message to the channel.
   */
  def !(msg: T)

  /**
   * Java API. <p/>
   * Sends the specified message to the channel.
   */
  def sendOneWay(msg: T) {
    this.!(msg)
  }
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
 *   actor.start()
 *   actor ! message
 *   actor.stop()
 * </pre>
 *
 * You can also create and start actors like this:
 * <pre>
 *   val actor = actorOf[MyActor].start()
 * </pre>
 *
 * Here is an example on how to create an actor with a non-default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf(new MyActor(...))
 *   actor.start()
 *   actor ! message
 *   actor.stop()
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ActorRef extends ActorRefShared with java.lang.Comparable[ActorRef] with Serializable { scalaRef: ScalaActorRef ⇒
  // Only mutable for RemoteServer in order to maintain identity across nodes
  @volatile
  protected[akka] var _uuid = newUuid
  @volatile
  protected[this] var _status: ActorRefInternals.StatusType = ActorRefInternals.UNSTARTED

  val address: String

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  @deprecated("Will be replaced by implicit-scoped timeout on all methods that needs it, will default to timeout specified in config", "1.1")
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
   * Akka Java API. <p/>
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  def setReceiveTimeout(timeout: Long) {
    this.receiveTimeout = Some(timeout)
  }
  def getReceiveTimeout: Option[Long] = receiveTimeout

  /**
   * Akka Java API. <p/>
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
  def getFaultHandler: FaultHandlingStrategy

  /**
   * Akka Java API. <p/>
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
  def setLifeCycle(lifeCycle: LifeCycle)
  def getLifeCycle: LifeCycle

  /**
   * Akka Java API. <p/>
   * The default dispatcher is the <tt>Dispatchers.globalDispatcher</tt>.
   * This means that all actors will share the same event-driven executor based dispatcher.
   * <p/>
   * You can override it so it fits the specific use-case that the actor is used for.
   * See the <tt>akka.dispatch.Dispatchers</tt> class for the different
   * dispatchers available.
   * <p/>
   * The default is also that all actors that are created and spawned from within this actor
   * is sharing the same dispatcher as its creator.
   */
  def setDispatcher(dispatcher: MessageDispatcher) { this.dispatcher = dispatcher }
  def getDispatcher: MessageDispatcher = dispatcher

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
  def getUuid = _uuid
  def uuid = _uuid

  /**
   * Akka Java API. <p/>
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def getSender: Option[ActorRef] = sender

  /**
   * Akka Java API. <p/>
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '!!' or '!!!', else None.
   */
  def getSenderFuture: Option[Promise[Any]] = senderFuture

  /**
   * Is the actor being restarted?
   */
  def isBeingRestarted: Boolean = _status == ActorRefInternals.BEING_RESTARTED

  /**
   * Is the actor running?
   */
  def isRunning: Boolean = _status match {
    case ActorRefInternals.BEING_RESTARTED | ActorRefInternals.RUNNING ⇒ true
    case _ ⇒ false
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
   * Only for internal use. UUID is effectively final.
   */
  protected[akka] def uuid_=(uid: Uuid) {
    _uuid = uid
  }

  /**
   * Akka Java API. <p/>
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   * <pre>
   * actor.sendOneWay(message);
   * </pre>
   * <p/>
   */
  def sendOneWay(message: AnyRef): Unit = {
    sendOneWay(message, null)
  }

  /**
   * Akka Java API. <p/>
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   * Allows you to pass along the sender of the message.
   * <p/>
   * <pre>
   * actor.sendOneWay(message, context);
   * </pre>
   * <p/>
   */
  def sendOneWay(message: AnyRef, sender: ActorRef) {
    this.!(message)(Option(sender))
  }

  /**
   * Akka Java API. <p/>
   * @see sendRequestReply(message: AnyRef, timeout: Long, sender: ActorRef)
   * Uses the default timeout of the Actor (setTimeout()) and omits the sender reference
   */
  def sendRequestReply(message: AnyRef): AnyRef = sendRequestReply(message, timeout, null)

  /**
   * Akka Java API. <p/>
   * @see sendRequestReply(message: AnyRef, timeout: Long, sender: ActorRef)
   * Uses the default timeout of the Actor (setTimeout())
   */
  def sendRequestReply(message: AnyRef, sender: ActorRef): AnyRef = sendRequestReply(message, timeout, sender)

  /**
   * Akka Java API. <p/>
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
        "]\n\tfrom [" + (if (sender ne null) sender.address else "nowhere") +
        "]\n\twith timeout [" + timeout +
        "]\n\ttimed out."))
      .asInstanceOf[AnyRef]
  }

  /**
   * Akka Java API. <p/>
   * @see sendRequestReplyFuture(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout()) and omits the sender
   */
  def sendRequestReplyFuture[T <: AnyRef](message: AnyRef): Future[T] = sendRequestReplyFuture(message, timeout, null).asInstanceOf[Future[T]]

  /**
   * Akka Java API. <p/>
   * @see sendRequestReplyFuture(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout())
   */
  def sendRequestReplyFuture[T <: AnyRef](message: AnyRef, sender: ActorRef): Future[T] = sendRequestReplyFuture(message, timeout, sender).asInstanceOf[Future[T]]

  /**
   *  Akka Java API. <p/>
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'sendOneWay' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReplyFuture</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def sendRequestReplyFuture[T <: AnyRef](message: AnyRef, timeout: Long, sender: ActorRef): Future[T] = !!!(message, timeout)(Option(sender)).asInstanceOf[Future[T]]

  /**
   * Akka Java API. <p/>
   * Forwards the message specified to this actor and preserves the original sender of the message
   */
  def forward(message: AnyRef, sender: ActorRef) {
    if (sender eq null) throw new IllegalArgumentException("The 'sender' argument to 'forward' can't be null")
    else forward(message)(Some(sender))
  }

  /**
   * Akka Java API. <p/>
   * Use <code>getContext().replyUnsafe(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def replyUnsafe(message: AnyRef) {
    reply(message)
  }

  /**
   * Akka Java API. <p/>
   * Use <code>getContext().replySafe(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  def replySafe(message: AnyRef): Boolean = reply_?(message)

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher)

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
  def exit() {
    stop()
  }

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop()

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field of the 'faultHandler' has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   */
  def link(actorRef: ActorRef)

  /**
   * Unlink the actor.
   */
  def unlink(actorRef: ActorRef)

  /**
   * Atomically start and link an actor.
   */
  def startLink(actorRef: ActorRef): ActorRef

  /**
   * Returns the mailbox size.
   */
  def mailboxSize = dispatcher.mailboxSize(this)

  /**
   * Akka Java API. <p/>
   * Returns the mailbox size.
   */
  def getMailboxSize: Int = mailboxSize

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef]

  /**
   * Akka Java API. <p/>
   * Returns the supervisor, if there is one.
   */
  def getSupervisor: ActorRef = supervisor getOrElse null

  /**
   * Returns an unmodifiable Java Map containing the linked actors,
   * please note that the backing map is thread-safe but not immutable
   */
  def linkedActors: JMap[Uuid, ActorRef]

  /**
   * Java API. <p/>
   * Returns an unmodifiable Java Map containing the linked actors,
   * please note that the backing map is thread-safe but not immutable
   */
  def getLinkedActors: JMap[Uuid, ActorRef] = linkedActors

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
   * Java API. <p/>
   * Abstraction for unification of sender and senderFuture for later reply
   */
  def getChannel: Channel[Any] = channel

  protected[akka] def invoke(messageHandle: MessageInvocation)

  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef])

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[Promise[T]]): Promise[T]

  protected[akka] def actorInstance: AtomicReference[Actor]

  protected[akka] def actor: Actor = actorInstance.get

  protected[akka] def supervisor_=(sup: Option[ActorRef])

  protected[akka] def mailbox: AnyRef
  protected[akka] def mailbox_=(value: AnyRef): AnyRef

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable)

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int])

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int])

  override def hashCode: Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[ActorRef] &&
      that.asInstanceOf[ActorRef].uuid == uuid
  }

  override def toString = "Actor[" + address + ":" + uuid + "]"
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalActorRef private[akka] (private[this] val actorFactory: () ⇒ Actor, val address: String)
  extends ActorRef with ScalaActorRef {
  protected[akka] val guard = new ReentrantGuard

  @volatile
  protected[akka] var _futureTimeout: Option[ScheduledFuture[AnyRef]] = None
  @volatile
  private[akka] lazy val _linkedActors = new ConcurrentHashMap[Uuid, ActorRef]
  @volatile
  private[akka] var _supervisor: Option[ActorRef] = None
  @volatile
  private var maxNrOfRetriesCount: Int = 0
  @volatile
  private var restartTimeWindowStartNanos: Long = 0L
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
    __address: String,
    __timeout: Long,
    __receiveTimeout: Option[Long],
    __lifeCycle: LifeCycle,
    __supervisor: Option[ActorRef],
    __hotswap: Stack[PartialFunction[Any, Unit]],
    __factory: () ⇒ Actor) = {
    this(__factory, __address)
    _uuid = __uuid
    timeout = __timeout
    receiveTimeout = __receiveTimeout
    lifeCycle = __lifeCycle
    _supervisor = __supervisor
    hotswap = __hotswap
    setActorSelfFields(actor, this)
    start()
  }

  // ========= PUBLIC FUNCTIONS =========

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher) {
    guard.withGuard {
      if (!isBeingRestarted) {
        if (!isRunning) _dispatcher = md
        else throw new ActorInitializationException(
          "Can not swap dispatcher for " + toString + " after it has been started")
      }
    }
  }

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher = _dispatcher

  /**
   * Starts up the actor and its message queue.
   */
  def start(): ActorRef = guard.withGuard {
    if (isShutdown) throw new ActorStartException(
      "Can't restart an actor that has been shut down with 'stop' or 'exit'")
    if (!isRunning) {
      dispatcher.attach(this)

      _status = ActorRefInternals.RUNNING

      // If we are not currently creating this ActorRef instance
      if ((actorInstance ne null) && (actorInstance.get ne null))
        initializeActorInstance

      checkReceiveTimeout //Schedule the initial Receive timeout
    }
    this
  }

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop() {
    guard.withGuard {
      if (isRunning) {
        receiveTimeout = None
        cancelReceiveTimeout
        dispatcher.detach(this)
        _status = ActorRefInternals.SHUTDOWN
        try {
          actor.postStop()
        } finally {
          currentMessage = null
          Actor.registry.unregister(this)

          if (ClusterModule.isEnabled) Actor.remote.unregister(this)

          setActorSelfFields(actorInstance.get, null)
        }
      } //else if (isBeingRestarted) throw new ActorKilledException("Actor [" + toString + "] is being restarted.")
    }
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
  def link(actorRef: ActorRef) {
    guard.withGuard {
      val actorRefSupervisor = actorRef.supervisor
      val hasSupervisorAlready = actorRefSupervisor.isDefined
      if (hasSupervisorAlready && actorRefSupervisor.get.uuid == uuid) return // we already supervise this guy
      else if (hasSupervisorAlready) throw new IllegalActorStateException(
        "Actor can only have one supervisor [" + actorRef + "], e.g. link(actor) fails")
      else {
        _linkedActors.put(actorRef.uuid, actorRef)
        actorRef.supervisor = Some(this)
      }
    }
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def unlink(actorRef: ActorRef) {
    guard.withGuard {
      if (_linkedActors.remove(actorRef.uuid) eq null)
        throw new IllegalActorStateException("Actor [" + actorRef + "] is not a linked actor, can't unlink")

      actorRef.supervisor = None
    }
  }

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLink(actorRef: ActorRef): ActorRef = guard.withGuard {
    link(actorRef)
    actorRef.start()
    actorRef
  }

  /**
   * Returns the mailbox.
   */
  def mailbox: AnyRef = _mailbox

  protected[akka] def mailbox_=(value: AnyRef): AnyRef = { _mailbox = value; value }

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef] = _supervisor

  // ========= AKKA PROTECTED FUNCTIONS =========
  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = {
    val inetaddr =
      if (ReflectiveAccess.RemoteModule.isEnabled) Actor.remote.address
      else ReflectiveAccess.RemoteModule.configDefaultAddress
    SerializedActorRef(uuid, address, inetaddr.getAddress.getHostAddress, inetaddr.getPort, timeout)
  }

  protected[akka] def supervisor_=(sup: Option[ActorRef]) {
    _supervisor = sup
  }

  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]) {
    dispatcher dispatchMessage MessageInvocation(this, message, senderOption, None)
  }

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[Promise[T]]): Promise[T] = {
    val future = if (senderFuture.isDefined) senderFuture else Some(new DefaultPromise[T](timeout))
    dispatcher dispatchMessage MessageInvocation(this, message, senderOption, future.asInstanceOf[Some[Promise[Any]]])
    future.get
  }

  /**
   * Callback for the dispatcher. This is the single entry point to the user Actor implementation.
   */
  protected[akka] def invoke(messageHandle: MessageInvocation) {
    guard.lock.lock()
    try {
      if (!isShutdown) {
        currentMessage = messageHandle
        try {
          try {
            cancelReceiveTimeout() // FIXME: leave this here?
            actor(messageHandle.message)
            currentMessage = null // reset current message after successful invocation
          } catch {
            case e: InterruptedException ⇒
              currentMessage = null // received message while actor is shutting down, ignore
            case e ⇒
              handleExceptionInDispatch(e, messageHandle.message)
          }
          finally {
            checkReceiveTimeout // Reschedule receive timeout
          }
        } catch {
          case e ⇒
            EventHandler.error(e, this, messageHandle.message.toString)
            throw e
        }
      }
    } finally { guard.lock.unlock() }
  }

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable) {
    faultHandler match {
      case AllForOneStrategy(trapExit, maxRetries, within) if trapExit.exists(_.isAssignableFrom(reason.getClass)) ⇒
        restartLinkedActors(reason, maxRetries, within)

      case OneForOneStrategy(trapExit, maxRetries, within) if trapExit.exists(_.isAssignableFrom(reason.getClass)) ⇒
        dead.restart(reason, maxRetries, within)

      case _ ⇒
        if (_supervisor.isDefined) notifySupervisorWithMessage(Exit(this, reason))
        else dead.stop()
    }
  }

  private def requestRestartPermission(maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Boolean = {

    val denied = if (maxNrOfRetries.isEmpty && withinTimeRange.isEmpty) { //Immortal
      false
    } else if (withinTimeRange.isEmpty) { // restrict number of restarts
      val retries = maxNrOfRetriesCount + 1
      maxNrOfRetriesCount = retries //Increment number of retries
      retries > maxNrOfRetries.get
    } else { // cannot restart more than N within M timerange
      val retries = maxNrOfRetriesCount + 1

      val windowStart = restartTimeWindowStartNanos
      val now = System.nanoTime
      //We are within the time window if it isn't the first restart, or if the window hasn't closed
      val insideWindow = if (windowStart == 0) false
      else (now - windowStart) <= TimeUnit.MILLISECONDS.toNanos(withinTimeRange.get)

      if (windowStart == 0 || !insideWindow) //(Re-)set the start of the window
        restartTimeWindowStartNanos = now

      //Reset number of restarts if window has expired, otherwise, increment it
      maxNrOfRetriesCount = if (windowStart != 0 && !insideWindow) 1 else retries //Increment number of retries

      val restartCountLimit = if (maxNrOfRetries.isDefined) maxNrOfRetries.get else 1

      //The actor is dead if it dies X times within the window of restart
      insideWindow && retries > restartCountLimit
    }

    denied == false //If we weren't denied, we have a go
  }

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    def performRestart() {
      val failedActor = actorInstance.get
      failedActor.preRestart(reason)
      val freshActor = newActor
      setActorSelfFields(failedActor, null) // Only null out the references if we could instantiate the new actor
      actorInstance.set(freshActor) // Assign it here so if preStart fails, we can null out the sef-refs next call
      freshActor.preStart()
      freshActor.postRestart(reason)
    }

    def tooManyRestarts() {
      notifySupervisorWithMessage(MaximumNumberOfRestartsWithinTimeRangeReached(this, maxNrOfRetries, withinTimeRange, reason))
      stop()
    }

    @tailrec
    def attemptRestart() {
      val success = if (requestRestartPermission(maxNrOfRetries, withinTimeRange)) {
        guard.withGuard[Boolean] {
          _status = ActorRefInternals.BEING_RESTARTED

          lifeCycle match {
            case Temporary ⇒
              shutDownTemporaryActor(this)
              true

            case _ ⇒ // either permanent or none where default is permanent
              val success = try {
                performRestart()
                true
              } catch {
                case e ⇒
                  EventHandler.error(e, this, "Exception in restart of Actor [%s]".format(toString))
                  false // an error or exception here should trigger a retry
              }
              finally {
                currentMessage = null
              }
              if (success) {
                _status = ActorRefInternals.RUNNING
                dispatcher.resume(this)
                restartLinkedActors(reason, maxNrOfRetries, withinTimeRange)
              }
              success
          }
        }
      } else {
        tooManyRestarts()
        true // done
      }

      if (success) () // alles gut
      else attemptRestart()
    }

    attemptRestart() // recur
  }

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) = {
    val i = _linkedActors.values.iterator
    while (i.hasNext) {
      val actorRef = i.next
      actorRef.lifeCycle match {
        // either permanent or none where default is permanent
        case Temporary ⇒ shutDownTemporaryActor(actorRef)
        case _         ⇒ actorRef.restart(reason, maxNrOfRetries, withinTimeRange)
      }
    }
  }

  def linkedActors: JMap[Uuid, ActorRef] = java.util.Collections.unmodifiableMap(_linkedActors)

  // ========= PRIVATE FUNCTIONS =========

  private[this] def newActor: Actor = {
    import Actor.{ actorRefInCreation ⇒ refStack }
    val stackBefore = refStack.get
    refStack.set(stackBefore.push(this))
    try {
      actorFactory()
    } finally {
      val stackAfter = refStack.get
      if (stackAfter.nonEmpty)
        refStack.set(if (stackAfter.head eq null) stackAfter.pop.pop else stackAfter.pop) //pop null marker plus self
    }
  } match {
    case null  ⇒ throw new ActorInitializationException("Actor instance passed to ActorRef can not be 'null'")
    case valid ⇒ valid
  }

  private def shutDownTemporaryActor(temporaryActor: ActorRef) {
    temporaryActor.stop()
    _linkedActors.remove(temporaryActor.uuid) // remove the temporary actor
    // if last temporary actor is gone, then unlink me from supervisor
    if (_linkedActors.isEmpty) notifySupervisorWithMessage(UnlinkAndStop(this))
    true
  }

  private def handleExceptionInDispatch(reason: Throwable, message: Any) {
    EventHandler.error(reason, this, message.toString)

    //Prevent any further messages to be processed until the actor has been restarted
    dispatcher.suspend(this)

    senderFuture.foreach(_.completeWithException(reason))

    if (supervisor.isDefined) notifySupervisorWithMessage(Exit(this, reason))
    else {
      lifeCycle match {
        case Temporary ⇒ shutDownTemporaryActor(this)
        case _         ⇒ dispatcher.resume(this) //Resume processing for this actor
      }
    }
  }

  private def notifySupervisorWithMessage(notification: LifeCycleMessage) {
    // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
    _supervisor.foreach { sup ⇒
      if (sup.isShutdown) { // if supervisor is shut down, game over for all linked actors
        //Scoped stop all linked actors, to avoid leaking the 'i' val
        {
          val i = _linkedActors.values.iterator
          while (i.hasNext) {
            i.next.stop()
            i.remove
          }
        }
        //Stop the actor itself
        stop
      } else sup ! notification // else notify supervisor
    }
  }

  private def setActorSelfFields(actor: Actor, value: ActorRef) {

    @tailrec
    def lookupAndSetSelfFields(clazz: Class[_], actor: Actor, value: ActorRef): Boolean = {
      val success = try {
        val selfField = clazz.getDeclaredField("self")
        val someSelfField = clazz.getDeclaredField("someSelf")
        selfField.setAccessible(true)
        someSelfField.setAccessible(true)
        selfField.set(actor, value)
        someSelfField.set(actor, if (value ne null) Some(value) else null)
        true
      } catch {
        case e: NoSuchFieldException ⇒ false
      }

      if (success) true
      else {
        val parent = clazz.getSuperclass
        if (parent eq null)
          throw new IllegalActorStateException(toString + " is not an Actor since it have not mixed in the 'Actor' trait")
        lookupAndSetSelfFields(parent, actor, value)
      }
    }

    lookupAndSetSelfFields(actor.getClass, actor, value)
  }

  private def initializeActorInstance() {
    actor.preStart() // run actor preStart
    Actor.registry.register(this)
  }

  protected[akka] def checkReceiveTimeout() {
    cancelReceiveTimeout()
    if (receiveTimeout.isDefined && dispatcher.mailboxSize(this) <= 0) { //Only reschedule if desired and there are currently no more messages to be processed
      _futureTimeout = Some(Scheduler.scheduleOnce(this, ReceiveTimeout, receiveTimeout.get, TimeUnit.MILLISECONDS))
    }
  }

  protected[akka] def cancelReceiveTimeout() {
    if (_futureTimeout.isDefined) {
      _futureTimeout.get.cancel(true)
      _futureTimeout = None
    }
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
  val remoteAddress: InetSocketAddress,
  val address: String,
  _timeout: Long,
  loader: Option[ClassLoader])
  extends ActorRef with ScalaActorRef {

  ClusterModule.ensureEnabled()

  timeout = _timeout

  // FIXME BAD, we should not have different ActorRefs

  start()

  def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]) {
    Actor.remote.send[Any](message, senderOption, None, remoteAddress, timeout, true, this, loader)
  }

  def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[Promise[T]]): Promise[T] = {
    val future = Actor.remote.send[T](
      message, senderOption, senderFuture,
      remoteAddress, timeout, false, this, loader)
    if (future.isDefined) future.get
    else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
  }

  def start(): ActorRef = synchronized {
    _status = ActorRefInternals.RUNNING
    this
  }

  def stop() {
    synchronized {
      if (_status == ActorRefInternals.RUNNING) {
        _status = ActorRefInternals.SHUTDOWN
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)
      }
    }
  }

  // ==== NOT SUPPORTED ====

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = {
    SerializedActorRef(uuid, address, remoteAddress.getAddress.getHostAddress, remoteAddress.getPort, timeout)
  }

  @deprecated("Will be removed without replacement, doesn't make any sense to have in the face of `become` and `unbecome`", "1.1")
  def actorClass: Class[_ <: Actor] = unsupported
  def dispatcher_=(md: MessageDispatcher) {
    unsupported
  }
  def dispatcher: MessageDispatcher = unsupported
  def link(actorRef: ActorRef) {
    unsupported
  }
  def unlink(actorRef: ActorRef) {
    unsupported
  }
  def startLink(actorRef: ActorRef): ActorRef = unsupported
  def supervisor: Option[ActorRef] = unsupported
  def linkedActors: JMap[Uuid, ActorRef] = unsupported
  protected[akka] def mailbox: AnyRef = unsupported
  protected[akka] def mailbox_=(value: AnyRef): AnyRef = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable) {
    unsupported
  }
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    unsupported
  }
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    unsupported
  }
  protected[akka] def invoke(messageHandle: MessageInvocation) {
    unsupported
  }
  protected[akka] def supervisor_=(sup: Option[ActorRef]) {
    unsupported
  }
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
   * Returns the address for the actor.
   */
  def address: String

  /**
   * Returns the uuid for the actor.
   */
  def uuid: Uuid
}

/**
 * This trait represents the Scala Actor API
 * There are implicit conversions in ../actor/Implicits.scala
 * from ActorRef -> ScalaActorRef and back
 */
trait ScalaActorRef extends ActorRefShared { ref: ActorRef ⇒

  /**
   * Address for actor, must be a unique one.
   */
  def address: String

  /**
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
   *  faultHandler = AllForOneStrategy(trapExit = List(classOf[Exception]), maxNrOfRetries, withinTimeRange)
   * </pre>
   * Or:
   * <pre>
   *  faultHandler = OneForOneStrategy(trapExit = List(classOf[Exception]), maxNrOfRetries, withinTimeRange)
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
  def senderFuture(): Option[Promise[Any]] = {
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
  def !(message: Any)(implicit sender: Option[ActorRef] = None) {
    if (isRunning) postMessageToMailbox(message, sender)
    else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
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

      try { future.await.resultOrException } catch { case e: FutureTimeoutException ⇒ None }
    } else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
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
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
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
    } else throw new ActorInitializationException("Actor has not been started, you need to invoke 'actor.start()' before using it")
  }

  /**
   * Use <code>self.reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def reply(message: Any) {
    if (!reply_?(message)) throw new IllegalActorStateException(
      "\n\tNo sender in scope, can't reply. " +
        "\n\tYou have probably: " +
        "\n\t\t1. Sent a message to an Actor from an instance that is NOT an Actor." +
        "\n\t\t2. Invoked a method on an TypedActor from an instance NOT an TypedActor." +
        "\n\tElse you might want to use 'reply_?' which returns Boolean(true) if succes and Boolean(false) if no sender in scope")
  }

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
}

case class SerializedActorRef(val uuid: Uuid,
                              val address: String,
                              val hostname: String,
                              val port: Int,
                              val timeout: Long) {
  @throws(classOf[java.io.ObjectStreamException])
  def readResolve(): AnyRef = Actor.registry.local.actorFor(uuid) match {
    case Some(actor) ⇒ actor
    case None ⇒
      if (ReflectiveAccess.RemoteModule.isEnabled)
        RemoteActorRef(new InetSocketAddress(hostname, port), address, timeout, None)
      else
        throw new IllegalStateException("Trying to deserialize ActorRef (" + this + ") but it's not found in the local registry and remoting is not enabled!")
  }
}
