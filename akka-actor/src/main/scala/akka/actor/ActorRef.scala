/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.event.EventHandler
import akka.dispatch._
import akka.config._
import akka.config.Supervision._
import akka.util._
import akka.serialization.{ Serializer, Serialization }
import ReflectiveAccess._
import ClusterModule._
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ScheduledFuture, ConcurrentHashMap, TimeUnit }
import java.util.{ Map ⇒ JMap }

import scala.collection.immutable.Stack
import scala.annotation.tailrec
import java.lang.{ UnsupportedOperationException, IllegalStateException }
import akka.japi.Creator

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
 * ActorRef configuration object, this is threadsafe and fully sharable
 *
 * Props() returns default configuration
 * FIXME document me
 */
object Props {
  final val defaultCreator: () ⇒ Actor = () ⇒ throw new UnsupportedOperationException("No actor creator specified!")
  final val defaultDeployId: String = ""
  final val defaultDispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher
  final val defaultTimeout: Timeout = Timeout(Duration(Actor.TIMEOUT, "millis"))
  final val defaultLifeCycle: LifeCycle = Permanent
  final val defaultFaultHandler: FaultHandlingStrategy = NoFaultHandlingStrategy
  final val defaultSupervisor: Option[ActorRef] = None
  final val defaultLocalOnly: Boolean = false

  /**
   * The default Props instance, uses the settings from the Props object starting with default*
   */
  final val default = new Props()

  /**
   * Returns a cached default implementation of Props
   */
  def apply(): Props = default

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied type using the default constructor
   */
  def apply[T <: Actor: ClassManifest]: Props =
    default.withCreator(implicitly[ClassManifest[T]].erasure.asInstanceOf[Class[_ <: Actor]].newInstance)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied class using the default constructor
   */
  def apply(actorClass: Class[_ <: Actor]): Props =
    default.withCreator(actorClass.newInstance)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk
   */
  def apply(creator: ⇒ Actor): Props = default.withCreator(creator)

  def apply(behavior: (ScalaActorRef with SelfActorRef) ⇒ Actor.Receive): Props =
    apply(new Actor { def receive = behavior(self) })
}

/**
 * ActorRef configuration object, this is thread safe and fully sharable
 */
case class Props(creator: () ⇒ Actor = Props.defaultCreator,
                 deployId: String = Props.defaultDeployId,
                 dispatcher: MessageDispatcher = Props.defaultDispatcher,
                 timeout: Timeout = Props.defaultTimeout,
                 lifeCycle: LifeCycle = Props.defaultLifeCycle,
                 faultHandler: FaultHandlingStrategy = Props.defaultFaultHandler,
                 supervisor: Option[ActorRef] = Props.defaultSupervisor,
                 localOnly: Boolean = Props.defaultLocalOnly) {
  /**
   * No-args constructor that sets all the default values
   * Java API
   */
  def this() = this(
    creator = Props.defaultCreator,
    deployId = Props.defaultDeployId,
    dispatcher = Props.defaultDispatcher,
    timeout = Props.defaultTimeout,
    lifeCycle = Props.defaultLifeCycle,
    faultHandler = Props.defaultFaultHandler,
    supervisor = Props.defaultSupervisor,
    localOnly = Props.defaultLocalOnly)

  /**
   * Returns a new Props with the specified creator set
   *  Scala API
   */
  def withCreator(c: ⇒ Actor) = copy(creator = () ⇒ c)

  /**
   * Returns a new Props with the specified creator set
   *  Java API
   */
  def withCreator(c: Creator[Actor]) = copy(creator = () ⇒ c.create)

  /**
   * Returns a new Props with the specified deployId set
   *  Java and Scala API
   */
  def withDeployId(id: String) = copy(deployId = if (id eq null) "" else id)

  /**
   * Returns a new Props with the specified dispatcher set
   *  Java API
   */
  def withDispatcher(d: MessageDispatcher) = copy(dispatcher = d)

  /**
   * Returns a new Props with the specified timeout set
   * Java API
   */
  def withTimeout(t: Timeout) = copy(timeout = t)

  /**
   * Returns a new Props with the specified lifecycle set
   * Java API
   */
  def withLifeCycle(l: LifeCycle) = copy(lifeCycle = l)

  /**
   * Returns a new Props with the specified faulthandler set
   * Java API
   */
  def withFaultHandler(f: FaultHandlingStrategy) = copy(faultHandler = f)

  /**
   * Returns a new Props with the specified supervisor set, if null, it's equivalent to withSupervisor(Option.none())
   * Java API
   */
  def withSupervisor(s: ActorRef) = copy(supervisor = Option(s))

  /**
   * Returns a new Props with the specified supervisor set
   * Java API
   */
  def withSupervisor(s: akka.japi.Option[ActorRef]) = copy(supervisor = s.asScala)

  /**
   * Returns a new Props with the specified supervisor set
   * Scala API
   */
  def withSupervisor(s: scala.Option[ActorRef]) = copy(supervisor = s)

  /**
   * Returns a new Props with the specified localOnly set
   * Java and Scala API
   */
  def withLocalOnly(l: Boolean) = copy(localOnly = l)
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
abstract class ActorRef extends ActorRefShared with UntypedChannel with ReplyChannel[Any] with java.lang.Comparable[ActorRef] with Serializable {
  scalaRef: ScalaActorRef ⇒
  // Only mutable for RemoteServer in order to maintain identity across nodes
  @volatile
  protected[akka] var _uuid = newUuid
  @volatile
  protected[this] var _status: ActorRefInternals.StatusType = ActorRefInternals.UNSTARTED

  def address: String

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
   * Only for internal use. UUID is effectively final.
   */
  protected[akka] def uuid_=(uid: Uuid) {
    _uuid = uid
  }

  protected[akka] def timeout: Long = Props.defaultTimeout.duration.toMillis //TODO Remove me if possible

  /**
   * Defines the life-cycle for a supervised actor.
   */
  protected[akka] def lifeCycle: LifeCycle = UndefinedLifeCycle //TODO Remove me if possible

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout()) and omits the sender
   */
  def ask(message: AnyRef): Future[AnyRef] = ask(message, timeout, null)

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the specified timeout (milliseconds)
   */
  def ask(message: AnyRef, timeout: Long): Future[Any] = ask(message, timeout, null)

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout())
   */
  def ask(message: AnyRef, sender: ActorRef): Future[AnyRef] = ask(message, timeout, sender)

  /**
   *  Akka Java API. <p/>
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'tell' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>ask</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def ask(message: AnyRef, timeout: Long, sender: ActorRef): Future[AnyRef] =
    ?(message, Timeout(timeout))(sender).asInstanceOf[Future[AnyRef]]

  /**
   * Akka Java API. <p/>
   * Forwards the message specified to this actor and preserves the original sender of the message
   */
  def forward(message: AnyRef, sender: SelfActorRef) {
    if (sender eq null) throw new IllegalArgumentException("The 'sender' argument to 'forward' can't be null")
    else forward(message)(sender)
  }

  /**
   * Starts up the actor and its message queue.
   */
  def start(): this.type

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
  def link(actorRef: ActorRef): ActorRef

  /**
   * Unlink the actor.
   */
  def unlink(actorRef: ActorRef): ActorRef

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef]

  /**
   * Akka Java API. <p/>
   * Returns the supervisor, if there is one.
   */
  def getSupervisor: ActorRef = supervisor getOrElse null

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any]

  protected[akka] def supervisor_=(sup: Option[ActorRef])

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int])

  override def hashCode: Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[ActorRef] &&
      that.asInstanceOf[ActorRef].uuid == uuid
  }

  override def toString = "Actor[%s:%s]".format(address, uuid)
}

abstract class SelfActorRef extends ActorRef with ForwardableChannel { self: LocalActorRef with ScalaActorRef ⇒
  /**
   *   Holds the hot swapped partial function.
   *   WARNING: DO NOT USE THIS, IT IS INTERNAL AKKA USE ONLY
   */
  @volatile
  protected[akka] var hotswap = Stack[PartialFunction[Any, Unit]]()

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
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  @deprecated("will be removed in 2.0, use channel instead", "1.2")
  def getSender: Option[ActorRef] = sender

  /**
   * Akka Java API. <p/>
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '?'/'ask', else None.
   */
  @deprecated("will be removed in 2.0, use channel instead", "1.2")
  def getSenderFuture: Option[Promise[Any]] = senderFuture

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  @deprecated("will be removed in 2.0, use channel instead", "1.2")
  def sender: Option[ActorRef]

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '?'/'ask', else None.
   */
  @deprecated("will be removed in 2.0, use channel instead", "1.2")
  def senderFuture(): Option[Promise[Any]]

  /**
   * Abstraction for unification of sender and senderFuture for later reply
   */
  def channel: UntypedChannel = self.currentMessage match {
    case null ⇒ NullChannel
    case msg  ⇒ msg.channel
  }

  /**
   * Akka Java API. <p/>
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  def setReceiveTimeout(timeout: Long): Unit = this.receiveTimeout = Some(timeout)

  /**
   * Akka Java API. <p/>
   * Gets the current receive timeout
   * When specified, the receive method should be able to handle a 'ReceiveTimeout' message.
   */
  def getReceiveTimeout: Option[Long] = receiveTimeout

  /**
   * Java API. <p/>
   * Abstraction for unification of sender and senderFuture for later reply
   */
  def getChannel: UntypedChannel = channel

  /**
   * Akka Scala & Java API
   * Use <code>self.reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed. This method  fails if the original sender of the message could not be determined with an
   * IllegalStateException.
   *
   * If you don't want deal with this IllegalStateException, but just a boolean, just use the <code>tryReply(...)</code>
   * version.
   *
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def reply(message: Any) = channel.!(message)(this)

  /**
   * Akka Scala & Java API
   * Use <code>tryReply(..)</code> to try reply with a message to the original sender of the message currently
   * being processed. This method
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   *
   * If you would rather have an exception, check the <code>reply(..)</code> version.
   */
  def tryReply(message: Any): Boolean = channel.tryTell(message)(this)

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
   * Scala API
   * Returns the dispatcher (MessageDispatcher) that is used for this Actor
   */
  def dispatcher: MessageDispatcher

  /**
   * Java API
   * Returns the dispatcher (MessageDispatcher) that is used for this Actor
   */
  final def getDispatcher(): MessageDispatcher = dispatcher

  /** INTERNAL API ONLY **/
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable)
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalActorRef private[akka] (private[this] val props: Props, val address: String)
  extends SelfActorRef with ScalaActorRef {

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
  protected[akka] var mailbox: AnyRef = _

  protected[akka] val actorInstance = guard.withGuard {
    new AtomicReference[Actor]({
      if (props.supervisor.isDefined) props.supervisor.get.link(this)
      newActor
    })
  } //TODO Why is the guard needed here?

  protected[akka] override def timeout: Long = props.timeout.duration.toMillis //TODO remove this if possible
  protected[akka] override def lifeCycle: LifeCycle = props.lifeCycle //TODO remove this if possible

  private def serializer: Serializer = //TODO Is this used or needed?
    try { Serialization.serializerFor(this.getClass) } catch {
      case e: Exception ⇒ throw new akka.config.ConfigurationException(
        "Could not create Serializer object for [" + this.getClass.getName + "]")
    }

  private lazy val hasReplicationStorage: Boolean = {
    import DeploymentConfig._
    isReplicated(replicationSchemeFor(Deployer.deploymentFor(address)).getOrElse(Transient))
  }

  private lazy val replicationStorage: Option[TransactionLog] = {
    import DeploymentConfig._
    val replicationScheme = replicationSchemeFor(Deployer.deploymentFor(address)).getOrElse(Transient)
    if (isReplicated(replicationScheme)) {
      if (isReplicatedWithTransactionLog(replicationScheme)) {
        EventHandler.debug(this, "Creating a transaction log for Actor [%s] with replication strategy [%s]".format(address, replicationScheme))

        Some(transactionLog.newLogFor(_uuid.toString, isWriteBehindReplication(replicationScheme), replicationScheme)) //TODO FIXME @jboner shouldn't this be address?
      } else if (isReplicatedWithDataGrid(replicationScheme)) {
        throw new ConfigurationException("Replication storage type \"data-grid\" is not yet supported")
      } else {
        throw new ConfigurationException("Unknown replication storage type [" + replicationScheme + "]")
      }
    } else None
  }

  // If it was started inside "newActor", initialize it
  if (isRunning) initializeActorInstance

  // used only for deserialization
  private[akka] def this(
    __uuid: Uuid,
    __address: String,
    __props: Props,
    __receiveTimeout: Option[Long],
    __hotswap: Stack[PartialFunction[Any, Unit]]) = {

    this(__props, __address)

    _uuid = __uuid
    hotswap = __hotswap
    receiveTimeout = __receiveTimeout
    setActorSelfFields(actorInstance.get(), this) //TODO Why is this needed?
    start()
  }

  // ========= PUBLIC FUNCTIONS =========

  /**
   * Returns the dispatcher (MessageDispatcher) that is used for this Actor
   */
  def dispatcher: MessageDispatcher = props.dispatcher

  /**
   * Starts up the actor and its message queue.
   */
  def start(): this.type = guard.withGuard[this.type] {
    if (isShutdown) throw new ActorStartException(
      "Can't restart an actor that has been shut down with 'stop' or 'exit'")
    if (!isRunning) {
      dispatcher.attach(this)
      _status = ActorRefInternals.RUNNING
      try {
        // If we are not currently creating this ActorRef instance
        if ((actorInstance ne null) && (actorInstance.get ne null))
          initializeActorInstance

        checkReceiveTimeout //Schedule the initial Receive timeout
      } catch {
        case e ⇒
          _status = ActorRefInternals.UNSTARTED
          throw e
      }
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
        Actor.registry.unregister(this)

        // This lines can trigger cluster start which makes cluster ZK client hang trying to reconnect indefinitely
        //if (ClusterModule.isEnabled) Actor.remote.unregister(this)
        _status = ActorRefInternals.SHUTDOWN
        dispatcher.detach(this)
        try {
          val a = actorInstance.get()
          if (Actor.debugLifecycle) EventHandler.debug(a, "stopping")
          a.postStop()
        } finally {
          currentMessage = null

          setActorSelfFields(actorInstance.get, null)
        }
      }

      if (hasReplicationStorage) replicationStorage.get.delete() //TODO shouldn't this be inside the if (isRunning?)
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
   * Returns the ref that was passed into it
   */
  def link(actorRef: ActorRef): ActorRef = {
    guard.withGuard {
      val actorRefSupervisor = actorRef.supervisor
      val hasSupervisorAlready = actorRefSupervisor.isDefined
      if (hasSupervisorAlready && actorRefSupervisor.get.uuid == uuid) return actorRef // we already supervise this guy
      else if (hasSupervisorAlready) throw new IllegalActorStateException(
        "Actor can only have one supervisor [" + actorRef + "], e.g. link(actor) fails")
      else {
        _linkedActors.put(actorRef.uuid, actorRef)
        actorRef.supervisor = Some(this)
      }
    }
    if (Actor.debugLifecycle) EventHandler.debug(actorInstance.get(), "now supervising " + actorRef)
    actorRef
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   * Returns the ref that was passed into it
   */
  def unlink(actorRef: ActorRef): ActorRef = {
    guard.withGuard {
      if (_linkedActors.remove(actorRef.uuid) eq null)
        throw new IllegalActorStateException("Actor [" + actorRef + "] is not a linked actor, can't unlink")
      actorRef.supervisor = None
      if (Actor.debugLifecycle) EventHandler.debug(actorInstance.get(), "stopped supervising " + actorRef)
    }
    actorRef
  }

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  @deprecated("will be removed in 2.0, use channel instead", "1.2")
  def sender: Option[ActorRef] = {
    val msg = currentMessage
    if (msg eq null) None
    else msg.channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
  }

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '?'/'ask', else None.
   */
  @deprecated("will be removed in 2.0, use channel instead", "1.2")
  def senderFuture(): Option[Promise[Any]] = {
    val msg = currentMessage
    if (msg eq null) None
    else msg.channel match {
      case f: ActorPromise ⇒ Some(f)
      case _               ⇒ None
    }
  }

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

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit =
    dispatcher dispatchMessage new MessageInvocation(this, message, channel)

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = {
    val future = channel match {
      case f: ActorPromise ⇒ f
      case _               ⇒ new ActorPromise(timeout)(dispatcher)
    }
    dispatcher dispatchMessage new MessageInvocation(this, message, future)
    future
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
            actorInstance.get().apply(messageHandle.message)
            currentMessage = null // reset current message after successful invocation
          } catch {
            case e: InterruptedException ⇒
              handleExceptionInDispatch(e, messageHandle.message)
              throw e
            case e ⇒
              handleExceptionInDispatch(e, messageHandle.message)
          } finally {
            checkReceiveTimeout // Reschedule receive timeout
          }
        } catch {
          case e ⇒
            EventHandler.error(e, actorInstance.get(), messageHandle.message.toString)
            throw e
        }
      } else {
        // throwing away message if actor is shut down, no use throwing an exception in receiving actor's thread, isShutdown is enforced on caller side
      }
    } finally {
      guard.lock.unlock()
      if (hasReplicationStorage) replicationStorage.get.recordEntry(messageHandle, this)
    }
  }

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable) {
    props.faultHandler match {
      case AllForOneStrategy(trapExit, maxRetries, within) if trapExit.exists(_.isAssignableFrom(reason.getClass)) ⇒
        restartLinkedActors(reason, maxRetries, within)

      case OneForOneStrategy(trapExit, maxRetries, within) if trapExit.exists(_.isAssignableFrom(reason.getClass)) ⇒
        dead.restart(reason, maxRetries, within)

      case _ ⇒
        if (_supervisor.isDefined) notifySupervisorWithMessage(Death(this, reason))
        else dead.stop()
    }
  }

  private def requestRestartPermission(maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Boolean = {

    val denied = if (maxNrOfRetries.isEmpty && withinTimeRange.isEmpty) {
      //Immortal
      false
    } else if (withinTimeRange.isEmpty) {
      // restrict number of restarts
      val retries = maxNrOfRetriesCount + 1
      maxNrOfRetriesCount = retries //Increment number of retries
      retries > maxNrOfRetries.get
    } else {
      // cannot restart more than N within M timerange
      val retries = maxNrOfRetriesCount + 1

      val windowStart = restartTimeWindowStartNanos
      val now = System.nanoTime
      //We are within the time window if it isn't the first restart, or if the window hasn't closed
      val insideWindow = if (windowStart == 0) true else (now - windowStart) <= TimeUnit.MILLISECONDS.toNanos(withinTimeRange.get)

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
      if (Actor.debugLifecycle) EventHandler.debug(failedActor, "restarting")
      val message = if (currentMessage ne null) Some(currentMessage.message) else None
      failedActor.preRestart(reason, message)
      val freshActor = newActor
      setActorSelfFields(failedActor, null) // Only null out the references if we could instantiate the new actor
      actorInstance.set(freshActor) // Assign it here so if preStart fails, we can null out the sef-refs next call
      freshActor.preStart()
      freshActor.postRestart(reason)
      if (Actor.debugLifecycle) EventHandler.debug(freshActor, "restarted")
    }

    def tooManyRestarts() {
      notifySupervisorWithMessage(
        MaximumNumberOfRestartsWithinTimeRangeReached(this, maxNrOfRetries, withinTimeRange, reason))
      stop()
    }

    @tailrec
    def attemptRestart() {
      val success = if (requestRestartPermission(maxNrOfRetries, withinTimeRange)) {
        guard.withGuard[Boolean] {
          _status = ActorRefInternals.BEING_RESTARTED

          lifeCycle match {
            case Temporary ⇒
              shutDownTemporaryActor(this, reason)
              true

            case _ ⇒ // either permanent or none where default is permanent
              val success =
                try {
                  performRestart()
                  true
                } catch {
                  case e ⇒
                    EventHandler.error(e, this, "Exception in restart of Actor [%s]".format(toString))
                    false // an error or exception here should trigger a retry
                } finally {
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
        case Temporary ⇒ shutDownTemporaryActor(actorRef, reason)
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
      if (_status == ActorRefInternals.BEING_RESTARTED) {
        val a = actorInstance.get()
        val fresh = try a.freshInstance catch {
          case e ⇒
            EventHandler.error(e, a, "freshInstance() failed, falling back to initial actor factory")
            None
        }
        fresh match {
          case Some(ref) ⇒ ref
          case None      ⇒ props.creator()
        }
      } else {
        props.creator()
      }
    } finally {
      val stackAfter = refStack.get
      if (stackAfter.nonEmpty)
        refStack.set(if (stackAfter.head eq null) stackAfter.pop.pop else stackAfter.pop) //pop null marker plus self
    }
  } match {
    case null  ⇒ throw new ActorInitializationException("Actor instance passed to ActorRef can not be 'null'")
    case valid ⇒ valid
  }

  private def shutDownTemporaryActor(temporaryActor: ActorRef, reason: Throwable) {
    temporaryActor.stop()
    _linkedActors.remove(temporaryActor.uuid) // remove the temporary actor
    // when this comes down through the handleTrapExit path, we get here when the temp actor is restarted
    notifySupervisorWithMessage(MaximumNumberOfRestartsWithinTimeRangeReached(temporaryActor, Some(0), None, reason))
    // if last temporary actor is gone, then unlink me from supervisor
    if (_linkedActors.isEmpty) notifySupervisorWithMessage(UnlinkAndStop(this))
    true
  }

  private def handleExceptionInDispatch(reason: Throwable, message: Any) {
    EventHandler.error(reason, this, message.toString)

    //Prevent any further messages to be processed until the actor has been restarted
    dispatcher.suspend(this)

    channel.sendException(reason)

    if (supervisor.isDefined) notifySupervisorWithMessage(Death(this, reason))
    else {
      lifeCycle match {
        case Temporary ⇒ shutDownTemporaryActor(this, reason)
        case _         ⇒ dispatcher.resume(this) //Resume processing for this actor
      }
    }
  }

  private def notifySupervisorWithMessage(notification: LifeCycleMessage) {
    // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
    _supervisor.foreach { sup ⇒
      if (sup.isShutdown) {
        // if supervisor is shut down, game over for all linked actors
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

    lookupAndSetSelfFields(actorInstance.get().getClass, actorInstance.get(), value)
  }

  private def initializeActorInstance() {
    val a = actorInstance.get()
    if (Actor.debugLifecycle) EventHandler.debug(a, "started")
    a.preStart() // run actor preStart
    Actor.registry.register(this)
  }

  protected[akka] def checkReceiveTimeout() {
    cancelReceiveTimeout()
    val recvtimeout = receiveTimeout
    if (recvtimeout.isDefined && dispatcher.mailboxIsEmpty(this)) {
      //Only reschedule if desired and there are currently no more messages to be processed
      _futureTimeout = Some(Scheduler.scheduleOnce(this, ReceiveTimeout, recvtimeout.get, TimeUnit.MILLISECONDS))
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

  start()

  protected[akka] override def timeout: Long = _timeout

  def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = {
    val chSender = if (channel.isInstanceOf[ActorRef]) Some(channel.asInstanceOf[ActorRef]) else None
    Actor.remote.send[Any](message, chSender, None, remoteAddress, timeout, true, this, loader)
  }

  def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = {

    val chSender = if (channel.isInstanceOf[ActorRef]) Some(channel.asInstanceOf[ActorRef]) else None
    val chFuture = if (channel.isInstanceOf[Promise[_]]) Some(channel.asInstanceOf[Promise[Any]]) else None
    val future = Actor.remote.send[Any](message, chSender, chFuture, remoteAddress, timeout.duration.toMillis, false, this, loader)

    if (future.isDefined) ActorPromise(future.get)
    else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
  }

  def start(): this.type = synchronized[this.type] {
    if (_status == ActorRefInternals.UNSTARTED)
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

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = {
    SerializedActorRef(uuid, address, remoteAddress.getAddress.getHostAddress, remoteAddress.getPort, timeout)
  }

  def link(actorRef: ActorRef): ActorRef = unsupported

  def unlink(actorRef: ActorRef): ActorRef = unsupported

  def supervisor: Option[ActorRef] = unsupported

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    unsupported
  }

  protected[akka] def supervisor_=(sup: Option[ActorRef]) {
    unsupported
  }

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
trait ScalaActorRef extends ActorRefShared with ReplyChannel[Any] {
  ref: ActorRef ⇒

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
  def !(message: Any)(implicit channel: UntypedChannel): Unit = {
    if (isRunning) postMessageToMailbox(message, channel)
    else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
  }

  /**
   * Sends a message asynchronously, returning a future which may eventually hold the reply.
   */
  def ?(message: Any)(implicit channel: UntypedChannel, timeout: Timeout): Future[Any] = {
    //FIXME: so it can happen that a message is posted after the actor has been shut down (the isRunning and postMessageToMailboxAndCreateFutureResultWithTimeout are not atomic.
    if (isRunning) {
      postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, channel)
      //FIXME: there is no after check if the running state is still true.. so no 'repairing'
    } else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
  }

  def ?(message: Any, timeout: Timeout)(implicit channel: UntypedChannel): Future[Any] = ?(message)(channel, timeout)

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!' and '?'/'ask'.
   */
  def forward(message: Any)(implicit channel: ForwardableChannel) = {
    //FIXME: so it can happen that a message is posted after the actor has been shut down (the isRunning and postMessageToMailbox are not atomic.
    if (isRunning) {
      postMessageToMailbox(message, channel.channel)
      //FIXME: there is no after check if the running state is still true.. so no 'repairing'
    } else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
  }
}

/**
 * Memento pattern for serializing ActorRefs transparently
 */
case class SerializedActorRef(uuid: Uuid,
                              address: String,
                              hostname: String,
                              port: Int,
                              timeout: Long) {
  @throws(classOf[java.io.ObjectStreamException])
  def readResolve(): AnyRef = Actor.registry.local.actorFor(uuid) match {
    case Some(actor) ⇒ actor
    case None ⇒
      //TODO FIXME Add case for when hostname+port == remote.address.hostname+port, should return a DeadActorRef or something
      if (ReflectiveAccess.RemoteModule.isEnabled)
        RemoteActorRef(new InetSocketAddress(hostname, port), address, timeout, None)
      else
        throw new IllegalStateException(
          "Trying to deserialize ActorRef [" + this +
            "] but it's not found in the local registry and remoting is not enabled.")
  }
}

/**
 * Trait for ActorRef implementations where most of the methods are not supported.
 */
trait UnsupportedActorRef extends ActorRef with ScalaActorRef {

  def link(actorRef: ActorRef): ActorRef = unsupported

  def unlink(actorRef: ActorRef): ActorRef = unsupported

  def supervisor: Option[ActorRef] = unsupported

  protected[akka] def supervisor_=(sup: Option[ActorRef]) {
    unsupported
  }

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    unsupported
  }

  private def unsupported = throw new UnsupportedOperationException("Not supported for %s".format(getClass.getName))
}
