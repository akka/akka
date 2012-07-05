/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import scala.annotation.tailrec
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.event.Logging.{ Debug, Warning, Error }
import akka.japi.Procedure
import java.io.{ NotSerializableException, ObjectOutputStream }
import akka.serialization.SerializationExtension
import akka.event.Logging.{ LogEventException, LogEvent }
import collection.immutable.{ TreeSet, TreeMap }
import akka.util.{ Unsafe, Duration, Helpers, NonFatal }
import java.util.concurrent.atomic.AtomicLong
import scala.collection.JavaConverters.asJavaIterableConverter

//TODO: everything here for current compatibility - could be limited more

/**
 * The actor context - the view of the actor cell from the actor.
 * Exposes contextual information for the actor and the current message.
 *
 * There are several possibilities for creating actors (see [[akka.actor.Props]]
 * for details on `props`):
 *
 * {{{
 * // Java or Scala
 * context.actorOf(props, "name")
 * context.actorOf(props)
 *
 * // Scala
 * context.actorOf(Props[MyActor]("name")
 * context.actorOf(Props[MyActor]
 * context.actorOf(Props(new MyActor(...))
 *
 * // Java
 * context.actorOf(classOf[MyActor]);
 * context.actorOf(Props(new Creator<MyActor>() {
 *   public MyActor create() { ... }
 * });
 * context.actorOf(Props(new Creator<MyActor>() {
 *   public MyActor create() { ... }
 * }, "name");
 * }}}
 *
 * Where no name is given explicitly, one will be automatically generated.
 */
trait ActorContext extends ActorRefFactory {

  def self: ActorRef

  /**
   * Retrieve the Props which were used to create this actor.
   */
  def props: Props

  /**
   * Gets the current receive timeout
   * When specified, the receive method should be able to handle a 'ReceiveTimeout' message.
   */
  def receiveTimeout: Option[Duration]

  /**
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   * 1 millisecond is the minimum supported timeout.
   */
  def setReceiveTimeout(timeout: Duration): Unit

  /**
   * Clears the receive timeout, i.e. deactivates this feature.
   */
  def resetReceiveTimeout(): Unit

  /**
   * Changes the Actor's behavior to become the new 'Receive' (PartialFunction[Any, Unit]) handler.
   * Puts the behavior on top of the hotswap stack.
   * If "discardOld" is true, an unbecome will be issued prior to pushing the new behavior to the stack
   */
  def become(behavior: Actor.Receive, discardOld: Boolean = true): Unit

  /**
   * Reverts the Actor behavior to the previous one in the hotswap stack.
   */
  def unbecome(): Unit

  /**
   * Returns the sender 'ActorRef' of the current message.
   */
  def sender: ActorRef

  /**
   * Returns all supervised children; this method returns a view onto the
   * internal collection of children. Targeted lookups should be using
   * `actorFor` instead for performance reasons:
   *
   * {{{
   * val badLookup = context.children find (_.path.name == "kid")
   * // should better be expressed as:
   * val goodLookup = context.actorFor("kid")
   * }}}
   */
  def children: Iterable[ActorRef]

  /**
   * Returns the dispatcher (MessageDispatcher) that is used for this Actor.
   * Importing this member will place a implicit MessageDispatcher in scope.
   */
  implicit def dispatcher: MessageDispatcher

  /**
   * The system that the actor belongs to.
   * Importing this member will place a implicit MessageDispatcher in scope.
   */
  implicit def system: ActorSystem

  /**
   * Returns the supervising parent ActorRef.
   */
  def parent: ActorRef

  /**
   * Registers this actor as a Monitor for the provided ActorRef.
   * @return the provided ActorRef
   */
  def watch(subject: ActorRef): ActorRef

  /**
   * Unregisters this actor as Monitor for the provided ActorRef.
   * @return the provided ActorRef
   */
  def unwatch(subject: ActorRef): ActorRef

  /**
   * ActorContexts shouldn't be Serializable
   */
  final protected def writeObject(o: ObjectOutputStream): Unit =
    throw new NotSerializableException("ActorContext is not serializable!")
}

/**
 * UntypedActorContext is the UntypedActor equivalent of ActorContext,
 * containing the Java API
 */
trait UntypedActorContext extends ActorContext {

  /**
   * Returns an unmodifiable Java Collection containing the linked actors,
   * please note that the backing map is thread-safe but not immutable
   */
  def getChildren(): java.lang.Iterable[ActorRef]

  /**
   * Changes the Actor's behavior to become the new 'Procedure' handler.
   * Puts the behavior on top of the hotswap stack.
   */
  def become(behavior: Procedure[Any]): Unit

  /**
   * Changes the Actor's behavior to become the new 'Procedure' handler.
   * Puts the behavior on top of the hotswap stack.
   * If "discardOld" is true, an unbecome will be issued prior to pushing the new behavior to the stack
   */
  def become(behavior: Procedure[Any], discardOld: Boolean): Unit

}

/**
 * INTERNAL API
 */
private[akka] trait Cell {
  /**
   * The “self” reference which this Cell is attached to.
   */
  def self: ActorRef
  /**
   * The system within which this Cell lives.
   */
  def system: ActorSystem
  /**
   * The system internals where this Cell lives.
   */
  def systemImpl: ActorSystemImpl
  /**
   * Recursively suspend this actor and all its children.
   */
  def suspend(): Unit
  /**
   * Recursively resume this actor and all its children.
   */
  def resume(inResponseToFailure: Boolean): Unit
  /**
   * Restart this actor (will recursively restart or stop all children).
   */
  def restart(cause: Throwable): Unit
  /**
   * Recursively terminate this actor and all its children.
   */
  def stop(): Unit
  /**
   * Returns “true” if the actor is locally known to be terminated, “false” if
   * alive or uncertain.
   */
  def isTerminated: Boolean
  /**
   * The supervisor of this actor.
   */
  def parent: InternalActorRef
  /**
   * All children of this actor, including only reserved-names.
   */
  def childrenRefs: ChildrenContainer
  /**
   * Enqueue a message to be sent to the actor; may or may not actually
   * schedule the actor to run, depending on which type of cell it is.
   */
  def tell(message: Any, sender: ActorRef): Unit
  /**
   * Enqueue a message to be sent to the actor; may or may not actually
   * schedule the actor to run, depending on which type of cell it is.
   */
  def sendSystemMessage(msg: SystemMessage): Unit
  /**
   * Returns true if the actor is local, i.e. if it is actually scheduled
   * on a Thread in the current JVM when run.
   */
  def isLocal: Boolean
  /**
   * If the actor isLocal, returns whether messages are currently queued,
   * “false” otherwise.
   */
  def hasMessages: Boolean
  /**
   * If the actor isLocal, returns the number of messages currently queued,
   * which may be a costly operation, 0 otherwise.
   */
  def numberOfMessages: Int
}

/**
 * Everything in here is completely Akka PRIVATE. You will not find any
 * supported APIs in this place. This is not the API you were looking
 * for! (waves hand)
 */
private[akka] object ActorCell {
  val contextStack = new ThreadLocal[List[ActorContext]] {
    override def initialValue: List[ActorContext] = Nil
  }

  final val emptyCancellable: Cancellable = new Cancellable {
    def isCancelled = false
    def cancel() {}
  }

  final val emptyReceiveTimeoutData: (Duration, Cancellable) = (Duration.Undefined, emptyCancellable)

  final val emptyBehaviorStack: List[Actor.Receive] = Nil

  final val emptyActorRefSet: Set[ActorRef] = TreeSet.empty
}

//ACTORCELL IS 64bytes and should stay that way unless very good reason not to (machine sympathy, cache line fit)
//vars don't need volatile since it's protected with the mailbox status
//Make sure that they are not read/written outside of a message processing (systemInvoke/invoke)
private[akka] class ActorCell(
  val system: ActorSystemImpl,
  val self: InternalActorRef,
  val props: Props,
  @volatile var parent: InternalActorRef) extends UntypedActorContext with Cell {

  import AbstractActorCell.{ childrenOffset, mailboxOffset, nextNameOffset }
  import ActorCell._
  import ChildrenContainer._

  final def isLocal = true

  final def systemImpl = system

  protected final def guardian = self

  protected final def lookupRoot = self

  final def provider = system.provider

  /*
   * RECEIVE TIMEOUT
   */

  var receiveTimeoutData: (Duration, Cancellable) = emptyReceiveTimeoutData

  override final def receiveTimeout: Option[Duration] = receiveTimeoutData._1 match {
    case Duration.Undefined ⇒ None
    case duration           ⇒ Some(duration)
  }

  final def setReceiveTimeout(timeout: Option[Duration]): Unit = setReceiveTimeout(timeout.getOrElse(Duration.Undefined))

  override final def setReceiveTimeout(timeout: Duration): Unit =
    receiveTimeoutData = (
      if (Duration.Undefined == timeout || timeout.toMillis < 1) Duration.Undefined else timeout,
      receiveTimeoutData._2)

  final override def resetReceiveTimeout(): Unit = setReceiveTimeout(None)

  /*
   * CHILDREN
   */

  @volatile
  private var _childrenRefsDoNotCallMeDirectly: ChildrenContainer = EmptyChildrenContainer

  def childrenRefs: ChildrenContainer = Unsafe.instance.getObjectVolatile(this, childrenOffset).asInstanceOf[ChildrenContainer]

  final def children: Iterable[ActorRef] = childrenRefs.children
  final def getChildren(): java.lang.Iterable[ActorRef] = asJavaIterableConverter(children).asJava

  def actorOf(props: Props): ActorRef = makeChild(this, props, randomName(), async = false)
  def actorOf(props: Props, name: String): ActorRef = makeChild(this, props, checkName(name), async = false)
  private[akka] def attachChild(props: Props): ActorRef = makeChild(this, props, randomName(), async = true)
  private[akka] def attachChild(props: Props, name: String): ActorRef = makeChild(this, props, checkName(name), async = true)

  @volatile private var _nextNameDoNotCallMeDirectly = 0L
  final protected def randomName(): String = {
    @tailrec def inc(): Long = {
      val current = Unsafe.instance.getLongVolatile(this, nextNameOffset)
      if (Unsafe.instance.compareAndSwapLong(this, nextNameOffset, current, current + 1)) current
      else inc()
    }
    Helpers.base64(inc())
  }

  final def stop(actor: ActorRef): Unit = {
    val started = actor match {
      case r: RepointableRef ⇒ r.isStarted
      case _                 ⇒ true
    }
    if (childrenRefs.getByRef(actor).isDefined && started) shallDie(this, actor)
    actor.asInstanceOf[InternalActorRef].stop()
  }

  /*
   * ACTOR STATE
   */

  var currentMessage: Envelope = _
  var actor: Actor = _
  private var behaviorStack: List[Actor.Receive] = emptyBehaviorStack
  var watching: Set[ActorRef] = emptyActorRefSet
  var watchedBy: Set[ActorRef] = emptyActorRefSet

  override final def watch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef ⇒
      if (a != self && !watching.contains(a)) {
        a.sendSystemMessage(Watch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
        watching += a
      }
      a
  }

  override final def unwatch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef ⇒
      if (a != self && watching.contains(a)) {
        a.sendSystemMessage(Unwatch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
        watching -= a
      }
      a
  }

  /*
   * MAILBOX and DISPATCHER
   */

  @volatile private var _mailboxDoNotCallMeDirectly: Mailbox = _ //This must be volatile since it isn't protected by the mailbox status

  @inline final def mailbox: Mailbox = Unsafe.instance.getObjectVolatile(this, mailboxOffset).asInstanceOf[Mailbox]

  @tailrec final def swapMailbox(newMailbox: Mailbox): Mailbox = {
    val oldMailbox = mailbox
    if (!Unsafe.instance.compareAndSwapObject(this, mailboxOffset, oldMailbox, newMailbox)) swapMailbox(newMailbox)
    else oldMailbox
  }

  final def hasMessages: Boolean = mailbox.hasMessages

  final def numberOfMessages: Int = mailbox.numberOfMessages

  val dispatcher: MessageDispatcher = system.dispatchers.lookup(props.dispatcher)

  /**
   * UntypedActorContext impl
   */
  final def getDispatcher(): MessageDispatcher = dispatcher

  final def isTerminated: Boolean = mailbox.isClosed

  final def start(): this.type = {

    /*
     * Create the mailbox and enqueue the Create() message to ensure that
     * this is processed before anything else.
     */
    swapMailbox(dispatcher.createMailbox(this))
    mailbox.setActor(this)

    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    mailbox.systemEnqueue(self, Create())

    // This call is expected to start off the actor by scheduling its mailbox.
    dispatcher.attach(this)

    this
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def suspend(): Unit = dispatcher.systemDispatch(this, Suspend())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def resume(inResponseToFailure: Boolean): Unit = dispatcher.systemDispatch(this, Resume(inResponseToFailure))

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def restart(cause: Throwable): Unit = dispatcher.systemDispatch(this, Recreate(cause))

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def stop(): Unit = dispatcher.systemDispatch(this, Terminate())

  def tell(message: Any, sender: ActorRef): Unit =
    dispatcher.dispatch(this, Envelope(message, if (sender eq null) system.deadLetters else sender, system))

  override def sendSystemMessage(message: SystemMessage): Unit = dispatcher.systemDispatch(this, message)

  /*
   * ACTOR CONTEXT IMPL
   */

  final def sender: ActorRef = currentMessage match {
    case null                      ⇒ system.deadLetters
    case msg if msg.sender ne null ⇒ msg.sender
    case _                         ⇒ system.deadLetters
  }

  def become(behavior: Actor.Receive, discardOld: Boolean = true): Unit =
    behaviorStack = behavior :: (if (discardOld && behaviorStack.nonEmpty) behaviorStack.tail else behaviorStack)

  def become(behavior: Procedure[Any]): Unit = become(behavior, false)

  def become(behavior: Procedure[Any], discardOld: Boolean): Unit =
    become({ case msg ⇒ behavior.apply(msg) }: Actor.Receive, discardOld)

  def unbecome(): Unit = {
    val original = behaviorStack
    behaviorStack =
      if (original.isEmpty || original.tail.isEmpty) actor.receive :: emptyBehaviorStack
      else original.tail
  }

  /*
   * FAILURE HANDLING
   */

  /* =================
   * T H E   R U L E S
   * =================
   * 
   * Actors can be suspended for two reasons:
   * - they fail
   * - their supervisor gets suspended
   * 
   * In particular they are not suspended multiple times because of cascading
   * own failures, i.e. while currentlyFailed() they do not fail again. In case
   * of a restart, failures in constructor/preStart count as new failures.
   */

  private def suspendNonRecursive(): Unit = dispatcher suspend this

  private def resumeNonRecursive(): Unit = dispatcher resume this

  /*
   * have we told our supervisor that we Failed() and have not yet heard back?
   * (actually: we might have heard back but not yet acted upon it, in case of
   * a restart with dying children)
   * might well be replaced by ref to a Cancellable in the future (see #2299)
   */
  private var _failed = false
  def currentlyFailed: Boolean = _failed
  def setFailed(): Unit = _failed = true
  def setNotFailed(): Unit = _failed = false

  //This method is in charge of setting up the contextStack and create a new instance of the Actor
  protected def newActor(): Actor = {
    contextStack.set(this :: contextStack.get)
    try {
      behaviorStack = emptyBehaviorStack
      val instance = props.creator.apply()

      if (instance eq null)
        throw new ActorInitializationException(self, "Actor instance passed to actorOf can't be 'null'")

      // If no becomes were issued, the actors behavior is its receive method
      behaviorStack = if (behaviorStack.isEmpty) instance.receive :: behaviorStack else behaviorStack
      instance
    } finally {
      val stackAfter = contextStack.get
      if (stackAfter.nonEmpty)
        contextStack.set(if (stackAfter.head eq null) stackAfter.tail.tail else stackAfter.tail) // pop null marker plus our context
    }
  }

  //Memory consistency is handled by the Mailbox (reading mailbox status then processing messages, then writing mailbox status
  final def systemInvoke(message: SystemMessage) {

    def create(): Unit = if (childrenRefs.isNormal) {
      try {
        val created = newActor()
        actor = created
        created.preStart()
        checkReceiveTimeout
        if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(created), "started (" + created + ")"))
      } catch {
        case NonFatal(i: InstantiationException) ⇒
          throw new ActorInitializationException(self,
            """exception during creation, this problem is likely to occur because the class of the Actor you tried to create is either,
               a non-static inner class (in which case make it a static inner class or use Props(new ...) or Props( new UntypedActorFactory ... )
               or is missing an appropriate, reachable no-args constructor.
            """, i.getCause)
        case NonFatal(e) ⇒ throw new ActorInitializationException(self, "exception during creation", e)
      }
    }

    def recreate(cause: Throwable): Unit =
      if (childrenRefs.isNormal) {
        val failedActor = actor
        if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(failedActor), "restarting"))
        if (failedActor ne null) {
          try {
            // if the actor fails in preRestart, we can do nothing but log it: it’s best-effort
            if (failedActor.context ne null) failedActor.preRestart(cause, Option(currentMessage))
          } catch {
            case NonFatal(e) ⇒
              val ex = new PreRestartException(self, e, cause, Option(currentMessage))
              publish(Error(ex, self.path.toString, clazz(failedActor), e.getMessage))
          } finally {
            clearActorFields(failedActor)
          }
        }
        assert(mailbox.isSuspended, "mailbox must be suspended during restart, status=" + mailbox.status)
        childrenRefs match {
          case ct: TerminatingChildrenContainer ⇒
            setChildrenTerminationReason(this, Recreation(cause))
          case _ ⇒
            doRecreate(cause, failedActor)
        }
      } else {
        // need to keep that suspend counter balanced
        doResume(inResponseToFailure = false)
      }

    def doSuspend(): Unit = {
      // done always to keep that suspend counter balanced
      suspendNonRecursive()
      childrenRefs.suspendChildren()
    }

    def doResume(inResponseToFailure: Boolean): Unit = {
      // done always to keep that suspend counter balanced
      // must happen “atomically”
      try resumeNonRecursive()
      finally if (inResponseToFailure) setNotFailed()
      childrenRefs.resumeChildren()
    }

    def addWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
      val watcheeSelf = watchee == self
      val watcherSelf = watcher == self

      if (watcheeSelf && !watcherSelf) {
        if (!watchedBy.contains(watcher)) {
          watchedBy += watcher
          if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "now monitoring " + watcher))
        }
      } else if (!watcheeSelf && watcherSelf) {
        watch(watchee)
      } else {
        publish(Warning(self.path.toString, clazz(actor), "BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, self)))
      }
    }

    def remWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
      val watcheeSelf = watchee == self
      val watcherSelf = watcher == self

      if (watcheeSelf && !watcherSelf) {
        if (watchedBy.contains(watcher)) {
          watchedBy -= watcher
          if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "stopped monitoring " + watcher))
        }
      } else if (!watcheeSelf && watcherSelf) {
        unwatch(watchee)
      } else {
        publish(Warning(self.path.toString, clazz(actor), "BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, self)))
      }
    }

    def terminate() {
      setReceiveTimeout(None)
      cancelReceiveTimeout

      // stop all children, which will turn childrenRefs into TerminatingChildrenContainer (if there are children)
      children foreach stop

      childrenRefs match {
        case ct: TerminatingChildrenContainer ⇒
          setChildrenTerminationReason(this, Termination)
          // do not process normal messages while waiting for all children to terminate
          suspendNonRecursive()
          if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "stopping"))
        case _ ⇒ doTerminate()
      }
    }

    def supervise(child: ActorRef): Unit = if (!childrenRefs.isTerminating) {
      if (childrenRefs.getByRef(child).isEmpty) addChild(this, child)
      handleSupervise(child)
      if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "now supervising " + child))
    }

    try {
      message match {
        case Create()                  ⇒ create()
        case Recreate(cause)           ⇒ recreate(cause)
        case Watch(watchee, watcher)   ⇒ addWatcher(watchee, watcher)
        case Unwatch(watchee, watcher) ⇒ remWatcher(watchee, watcher)
        case Suspend()                 ⇒ doSuspend()
        case Resume(inRespToFailure)   ⇒ doResume(inRespToFailure)
        case Terminate()               ⇒ terminate()
        case Supervise(child)          ⇒ supervise(child)
        case ChildTerminated(child)    ⇒ handleChildTerminated(child)
        case NoMessage                 ⇒ // only here to suppress warning
      }
    } catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒ handleInvokeFailure(e, "error while processing " + message)
    }
  }

  //Memory consistency is handled by the Mailbox (reading mailbox status then processing messages, then writing mailbox status
  final def invoke(messageHandle: Envelope): Unit = try {
    currentMessage = messageHandle
    cancelReceiveTimeout() // FIXME: leave this here???
    messageHandle.message match {
      case msg: AutoReceivedMessage ⇒ autoReceiveMessage(messageHandle)
      case msg                      ⇒ receiveMessage(msg)
    }
    currentMessage = null // reset current message after successful invocation
  } catch {
    case e @ (_: InterruptedException | NonFatal(_)) ⇒ handleInvokeFailure(e, e.getMessage)
  } finally {
    checkReceiveTimeout // Reschedule receive timeout
  }

  final def handleInvokeFailure(t: Throwable, message: String): Unit = {
    publish(Error(t, self.path.toString, clazz(actor), message))
    // prevent any further messages to be processed until the actor has been restarted
    if (!currentlyFailed) {
      // suspend self; these two must happen “atomically”
      try suspendNonRecursive()
      finally setFailed()
      // suspend children
      val skip: Set[ActorRef] = currentMessage match {
        case Envelope(Failed(`t`), child) ⇒ Set(child)
        case _                            ⇒ Set.empty
      }
      childrenRefs.suspendChildren(skip)
      // tell supervisor
      t match { // Wrap InterruptedExceptions and rethrow
        case _: InterruptedException ⇒ parent.tell(Failed(new ActorInterruptedException(t)), self); throw t
        case _                       ⇒ parent.tell(Failed(t), self)
      }
    }
  }

  def autoReceiveMessage(msg: Envelope): Unit = {
    if (system.settings.DebugAutoReceive)
      publish(Debug(self.path.toString, clazz(actor), "received AutoReceiveMessage " + msg))

    msg.message match {
      case Failed(cause)            ⇒ handleFailure(sender, cause)
      case t: Terminated            ⇒ watching -= t.actor; receiveMessage(t)
      case Kill                     ⇒ throw new ActorKilledException("Kill")
      case PoisonPill               ⇒ self.stop()
      case SelectParent(m)          ⇒ parent.tell(m, msg.sender)
      case SelectChildName(name, m) ⇒ for (c ← childrenRefs getByName name) c.child.tell(m, msg.sender)
      case SelectChildPattern(p, m) ⇒ for (c ← children if p.matcher(c.path.name).matches) c.tell(m, msg.sender)
    }
  }

  final def receiveMessage(msg: Any): Unit = {
    //FIXME replace with behaviorStack.head.applyOrElse(msg, unhandled) + "-optimize"
    val head = behaviorStack.head
    if (head.isDefinedAt(msg)) head.apply(msg) else actor.unhandled(msg)
  }

  private def doTerminate() {
    val a = actor
    try if (a ne null) a.postStop()
    finally try dispatcher.detach(this)
    finally try parent.sendSystemMessage(ChildTerminated(self))
    finally try
      if (!watchedBy.isEmpty) {
        val terminated = Terminated(self)(existenceConfirmed = true)
        try {
          watchedBy foreach {
            watcher ⇒
              try watcher.tell(terminated, self) catch {
                case NonFatal(t) ⇒ publish(Error(t, self.path.toString, clazz(a), "deathwatch"))
              }
          }
        } finally watchedBy = emptyActorRefSet
      }
    finally try
      if (!watching.isEmpty) {
        try {
          watching foreach { // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
            case watchee: InternalActorRef ⇒ try watchee.sendSystemMessage(Unwatch(watchee, self)) catch {
              case NonFatal(t) ⇒ publish(Error(t, self.path.toString, clazz(a), "deathwatch"))
            }
          }
        } finally watching = emptyActorRefSet
      }
    finally {
      if (system.settings.DebugLifecycle)
        publish(Debug(self.path.toString, clazz(a), "stopped"))
      behaviorStack = emptyBehaviorStack
      clearActorFields(a)
      actor = null
    }
  }

  private def doRecreate(cause: Throwable, failedActor: Actor): Unit = try {
    // must happen “atomically”
    try resumeNonRecursive()
    finally setNotFailed()

    val survivors = children

    val freshActor = newActor()
    actor = freshActor // this must happen before postRestart has a chance to fail
    if (freshActor eq failedActor) setActorFields(freshActor, this, self) // If the creator returns the same instance, we need to restore our nulled out fields.

    freshActor.postRestart(cause)
    if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(freshActor), "restarted"))

    // only after parent is up and running again do restart the children which were not stopped
    survivors foreach (child ⇒
      try child.asInstanceOf[InternalActorRef].restart(cause)
      catch {
        case NonFatal(e) ⇒ publish(Error(e, self.path.toString, clazz(freshActor), "restarting " + child))
      })
  } catch {
    case NonFatal(e) ⇒
      clearActorFields(actor) // in order to prevent preRestart() from happening again
      handleInvokeFailure(new PostRestartException(self, e, cause), e.getMessage)
  }

  final def handleFailure(child: ActorRef, cause: Throwable): Unit = childrenRefs.getByRef(child) match {
    case Some(stats) ⇒ if (!actor.supervisorStrategy.handleFailure(this, child, cause, stats, childrenRefs.stats)) throw cause
    case None        ⇒ publish(Warning(self.path.toString, clazz(actor), "dropping Failed(" + cause + ") from unknown child " + child))
  }

  final def handleChildTerminated(child: ActorRef): Unit = try {
    childrenRefs match {
      case TerminatingChildrenContainer(_, _, reason) ⇒
        val n = removeChild(this, child)
        actor.supervisorStrategy.handleChildTerminated(this, child, children)
        if (!n.isInstanceOf[TerminatingChildrenContainer]) reason match {
          case Recreation(cause) ⇒ doRecreate(cause, actor) // doRecreate since this is the continuation of "recreate"
          case Termination       ⇒ doTerminate()
          case _                 ⇒
        }
      case _ ⇒
        removeChild(this, child)
        actor.supervisorStrategy.handleChildTerminated(this, child, children)
    }
  } catch {
    case NonFatal(e) ⇒ handleInvokeFailure(e, "handleChildTerminated failed")
  }

  protected def handleSupervise(child: ActorRef): Unit = child match {
    case r: RepointableActorRef ⇒ r.activate()
    case _                      ⇒
  }

  final def checkReceiveTimeout() {
    val recvtimeout = receiveTimeoutData
    if (Duration.Undefined != recvtimeout._1 && !mailbox.hasMessages) {
      recvtimeout._2.cancel() //Cancel any ongoing future
      //Only reschedule if desired and there are currently no more messages to be processed
      receiveTimeoutData = (recvtimeout._1, system.scheduler.scheduleOnce(recvtimeout._1, self, ReceiveTimeout))
    } else cancelReceiveTimeout()

  }

  final def cancelReceiveTimeout(): Unit =
    if (receiveTimeoutData._2 ne emptyCancellable) {
      receiveTimeoutData._2.cancel()
      receiveTimeoutData = (receiveTimeoutData._1, emptyCancellable)
    }

  final def clearActorFields(actorInstance: Actor): Unit = {
    setActorFields(actorInstance, context = null, self = system.deadLetters)
    currentMessage = null
  }

  final def setActorFields(actorInstance: Actor, context: ActorContext, self: ActorRef) {
    @tailrec
    def lookupAndSetField(clazz: Class[_], actor: Actor, name: String, value: Any): Boolean = {
      val success = try {
        val field = clazz.getDeclaredField(name)
        field.setAccessible(true)
        field.set(actor, value)
        true
      } catch {
        case e: NoSuchFieldException ⇒ false
      }

      if (success) true
      else {
        val parent = clazz.getSuperclass
        if (parent eq null) throw new IllegalActorStateException(toString + " is not an Actor since it have not mixed in the 'Actor' trait")
        lookupAndSetField(parent, actor, name, value)
      }
    }
    if (actorInstance ne null) {
      lookupAndSetField(actorInstance.getClass, actorInstance, "context", context)
      lookupAndSetField(actorInstance.getClass, actorInstance, "self", self)
    }
  }

  // logging is not the main purpose, and if it fails there’s nothing we can do
  private final def publish(e: LogEvent): Unit = try system.eventStream.publish(e) catch { case NonFatal(_) ⇒ }

  private final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass
}

