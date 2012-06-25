/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.existentials

import akka.dispatch._
import scala.annotation.tailrec
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.event.Logging.{ Debug, Warning, Error }
import akka.japi.Procedure
import java.io.{ NotSerializableException, ObjectOutputStream }
import akka.serialization.SerializationExtension
import akka.event.Logging.LogEventException
import collection.immutable.{ TreeSet, TreeMap }
import akka.util.{ Unsafe, Duration, Helpers, NonFatal }
import java.util.concurrent.atomic.AtomicLong

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
  def resume(): Unit
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
  def childrenRefs: ActorCell.ChildrenContainer
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

  final val emptyReceiveTimeoutData: (Long, Cancellable) = (-1, emptyCancellable)

  final val emptyBehaviorStack: List[Actor.Receive] = Nil

  final val emptyActorRefSet: Set[ActorRef] = TreeSet.empty

  sealed trait SuspendReason
  case object UserRequest extends SuspendReason
  case class Recreation(cause: Throwable) extends SuspendReason
  case object Termination extends SuspendReason

  trait ChildrenContainer {
    def add(child: ActorRef): ChildrenContainer
    def remove(child: ActorRef): ChildrenContainer
    def getByName(name: String): Option[ChildRestartStats]
    def getByRef(actor: ActorRef): Option[ChildRestartStats]
    def children: Iterable[ActorRef]
    def stats: Iterable[ChildRestartStats]
    def shallDie(actor: ActorRef): ChildrenContainer
    /**
     * reserve that name or throw an exception
     */
    def reserve(name: String): ChildrenContainer
    /**
     * cancel a reservation
     */
    def unreserve(name: String): ChildrenContainer
  }

  trait EmptyChildrenContainer extends ChildrenContainer {
    val emptyStats = TreeMap.empty[String, ChildStats]
    def add(child: ActorRef): ChildrenContainer =
      new NormalChildrenContainer(emptyStats.updated(child.path.name, ChildRestartStats(child)))
    def remove(child: ActorRef): ChildrenContainer = this
    def getByName(name: String): Option[ChildRestartStats] = None
    def getByRef(actor: ActorRef): Option[ChildRestartStats] = None
    def children: Iterable[ActorRef] = Nil
    def stats: Iterable[ChildRestartStats] = Nil
    def shallDie(actor: ActorRef): ChildrenContainer = this
    def reserve(name: String): ChildrenContainer = new NormalChildrenContainer(emptyStats.updated(name, ChildNameReserved))
    def unreserve(name: String): ChildrenContainer = this
    override def toString = "no children"
  }

  /**
   * This is the empty container, shared among all leaf actors.
   */
  object EmptyChildrenContainer extends EmptyChildrenContainer

  /**
   * This is the empty container which is installed after the last child has
   * terminated while stopping; it is necessary to distinguish from the normal
   * empty state while calling handleChildTerminated() for the last time.
   */
  object TerminatedChildrenContainer extends EmptyChildrenContainer {
    override def add(child: ActorRef): ChildrenContainer = this
    override def reserve(name: String): ChildrenContainer =
      throw new IllegalStateException("cannot reserve actor name '" + name + "': already terminated")
  }

  /**
   * Normal children container: we do have at least one child, but none of our
   * children are currently terminating (which is the time period between
   * calling context.stop(child) and processing the ChildTerminated() system
   * message).
   */
  class NormalChildrenContainer(c: TreeMap[String, ChildStats]) extends ChildrenContainer {

    def add(child: ActorRef): ChildrenContainer =
      new NormalChildrenContainer(c.updated(child.path.name, ChildRestartStats(child)))

    def remove(child: ActorRef): ChildrenContainer = NormalChildrenContainer(c - child.path.name)

    def getByName(name: String): Option[ChildRestartStats] = c.get(name) match {
      case s @ Some(_: ChildRestartStats) ⇒ s.asInstanceOf[Option[ChildRestartStats]]
      case _                              ⇒ None
    }

    def getByRef(actor: ActorRef): Option[ChildRestartStats] = c.get(actor.path.name) match {
      case c @ Some(crs: ChildRestartStats) if (crs.child == actor) ⇒ c.asInstanceOf[Option[ChildRestartStats]]
      case _ ⇒ None
    }

    def children: Iterable[ActorRef] = c.values.view.collect { case ChildRestartStats(child, _, _) ⇒ child }

    def stats: Iterable[ChildRestartStats] = c.values.collect { case c: ChildRestartStats ⇒ c }

    def shallDie(actor: ActorRef): ChildrenContainer = TerminatingChildrenContainer(c, Set(actor), UserRequest)

    def reserve(name: String): ChildrenContainer =
      if (c contains name)
        throw new InvalidActorNameException("actor name " + name + " is not unique!")
      else new NormalChildrenContainer(c.updated(name, ChildNameReserved))

    def unreserve(name: String): ChildrenContainer = c.get(name) match {
      case Some(ChildNameReserved) ⇒ NormalChildrenContainer(c - name)
      case _                       ⇒ this
    }

    override def toString =
      if (c.size > 20) c.size + " children"
      else c.mkString("children:\n    ", "\n    ", "")
  }

  object NormalChildrenContainer {
    def apply(c: TreeMap[String, ChildStats]): ChildrenContainer =
      if (c.isEmpty) EmptyChildrenContainer
      else new NormalChildrenContainer(c)
  }

  /**
   * Waiting state: there are outstanding termination requests (i.e. context.stop(child)
   * was called but the corresponding ChildTerminated() system message has not yet been
   * processed). There could be no specific reason (UserRequested), we could be Restarting
   * or Terminating.
   *
   * Removing the last child which was supposed to be terminating will return a different
   * type of container, depending on whether or not children are left and whether or not
   * the reason was “Terminating”.
   */
  case class TerminatingChildrenContainer(c: TreeMap[String, ChildStats], toDie: Set[ActorRef], reason: SuspendReason)
    extends ChildrenContainer {

    def add(child: ActorRef): ChildrenContainer = copy(c.updated(child.path.name, ChildRestartStats(child)))

    def remove(child: ActorRef): ChildrenContainer = {
      val t = toDie - child
      if (t.isEmpty) reason match {
        case Termination ⇒ TerminatedChildrenContainer
        case _           ⇒ NormalChildrenContainer(c - child.path.name)
      }
      else copy(c - child.path.name, t)
    }

    def getByName(name: String): Option[ChildRestartStats] = c.get(name) match {
      case s @ Some(_: ChildRestartStats) ⇒ s.asInstanceOf[Option[ChildRestartStats]]
      case _                              ⇒ None
    }

    def getByRef(actor: ActorRef): Option[ChildRestartStats] = c.get(actor.path.name) match {
      case c @ Some(crs: ChildRestartStats) if (crs.child == actor) ⇒ c.asInstanceOf[Option[ChildRestartStats]]
      case _ ⇒ None
    }

    def children: Iterable[ActorRef] = c.values.view.collect { case ChildRestartStats(child, _, _) ⇒ child }

    def stats: Iterable[ChildRestartStats] = c.values.collect { case c: ChildRestartStats ⇒ c }

    def shallDie(actor: ActorRef): ChildrenContainer = copy(toDie = toDie + actor)

    def reserve(name: String): ChildrenContainer = reason match {
      case Termination ⇒ throw new IllegalStateException("cannot reserve actor name '" + name + "': terminating")
      case _ ⇒
        if (c contains name)
          throw new InvalidActorNameException("actor name " + name + " is not unique!")
        else copy(c = c.updated(name, ChildNameReserved))
    }

    def unreserve(name: String): ChildrenContainer = c.get(name) match {
      case Some(ChildNameReserved) ⇒ copy(c = c - name)
      case _                       ⇒ this
    }

    override def toString =
      if (c.size > 20) c.size + " children"
      else c.mkString("children (" + toDie.size + " terminating):\n    ", "\n    ", "\n") + toDie
  }
}

//ACTORCELL IS 64bytes and should stay that way unless very good reason not to (machine sympathy, cache line fit)
//vars don't need volatile since it's protected with the mailbox status
//Make sure that they are not read/written outside of a message processing (systemInvoke/invoke)
private[akka] class ActorCell(
  val system: ActorSystemImpl,
  val self: InternalActorRef,
  val props: Props,
  @volatile var parent: InternalActorRef) extends UntypedActorContext with Cell {

  import AbstractActorCell.{ mailboxOffset, childrenOffset, nextNameOffset }
  import ActorCell._

  final def isLocal = true

  final def systemImpl = system

  protected final def guardian = self

  protected final def lookupRoot = self

  final def provider = system.provider

  override final def receiveTimeout: Option[Duration] = if (receiveTimeoutData._1 > 0) Some(Duration(receiveTimeoutData._1, MILLISECONDS)) else None

  override final def setReceiveTimeout(timeout: Duration): Unit = setReceiveTimeout(Some(timeout))

  final def setReceiveTimeout(timeout: Option[Duration]): Unit = {
    val timeoutMs = timeout match {
      case None ⇒ -1L
      case Some(duration) ⇒
        val ms = duration.toMillis
        if (ms <= 0) -1L
        // 1 millisecond is minimum supported
        else if (ms < 1) 1L
        else ms
    }
    receiveTimeoutData = (timeoutMs, receiveTimeoutData._2)
  }

  final override def resetReceiveTimeout(): Unit = setReceiveTimeout(None)

  /**
   * In milliseconds
   */
  var receiveTimeoutData: (Long, Cancellable) = emptyReceiveTimeoutData

  @volatile
  private var _childrenRefsDoNotCallMeDirectly: ChildrenContainer = EmptyChildrenContainer

  def childrenRefs: ChildrenContainer = Unsafe.instance.getObjectVolatile(this, childrenOffset).asInstanceOf[ChildrenContainer]

  private def swapChildrenRefs(oldChildren: ChildrenContainer, newChildren: ChildrenContainer): Boolean =
    Unsafe.instance.compareAndSwapObject(this, childrenOffset, oldChildren, newChildren)

  @tailrec private def reserveChild(name: String): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.reserve(name)) || reserveChild(name)
  }

  @tailrec private def unreserveChild(name: String): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.unreserve(name)) || unreserveChild(name)
  }

  @tailrec private def addChild(ref: ActorRef): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.add(ref)) || addChild(ref)
  }

  @tailrec private def shallDie(ref: ActorRef): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.shallDie(ref)) || shallDie(ref)
  }

  @tailrec private def removeChild(ref: ActorRef): ChildrenContainer = {
    val c = childrenRefs
    val n = c.remove(ref)
    if (swapChildrenRefs(c, n)) n
    else removeChild(ref)
  }

  @tailrec private def setChildrenTerminationReason(reason: SuspendReason): Boolean = {
    childrenRefs match {
      case c: TerminatingChildrenContainer ⇒ swapChildrenRefs(c, c.copy(reason = reason)) || setChildrenTerminationReason(reason)
      case _                               ⇒ false
    }
  }

  private def isTerminating = childrenRefs match {
    case TerminatingChildrenContainer(_, _, Termination) ⇒ true
    case TerminatedChildrenContainer ⇒ true
    case _ ⇒ false
  }
  private def isNormal = childrenRefs match {
    case TerminatingChildrenContainer(_, _, Termination | _: Recreation) ⇒ false
    case _ ⇒ true
  }

  private def _actorOf(props: Props, name: String, async: Boolean): ActorRef = {
    if (system.settings.SerializeAllCreators && !props.creator.isInstanceOf[NoSerializationVerificationNeeded]) {
      val ser = SerializationExtension(system)
      ser.serialize(props.creator) match {
        case Left(t) ⇒ throw t
        case Right(bytes) ⇒ ser.deserialize(bytes, props.creator.getClass) match {
          case Left(t) ⇒ throw t
          case _       ⇒ //All good
        }
      }
    }
    /*
     * in case we are currently terminating, fail external attachChild requests
     * (internal calls cannot happen anyway because we are suspended)
     */
    if (isTerminating) throw new IllegalStateException("cannot create children while terminating or terminated")
    else {
      reserveChild(name)
      // this name will either be unreserved or overwritten with a real child below
      val actor =
        try {
          provider.actorOf(systemImpl, props, self, self.path / name,
            systemService = false, deploy = None, lookupDeploy = true, async = async)
        } catch {
          case NonFatal(e) ⇒
            unreserveChild(name)
            throw e
        }
      addChild(actor)
      actor
    }
  }

  def actorOf(props: Props): ActorRef = _actorOf(props, randomName(), async = false)

  def actorOf(props: Props, name: String): ActorRef = _actorOf(props, checkName(name), async = false)

  private def checkName(name: String): String = {
    import ActorPath.ElementRegex
    name match {
      case null           ⇒ throw new InvalidActorNameException("actor name must not be null")
      case ""             ⇒ throw new InvalidActorNameException("actor name must not be empty")
      case ElementRegex() ⇒ name
      case _              ⇒ throw new InvalidActorNameException("illegal actor name '" + name + "', must conform to " + ElementRegex)
    }
  }

  private[akka] def attachChild(props: Props, name: String): ActorRef =
    _actorOf(props, checkName(name), async = true)

  private[akka] def attachChild(props: Props): ActorRef =
    _actorOf(props, randomName(), async = true)

  final def stop(actor: ActorRef): Unit = {
    val started = actor match {
      case r: RepointableRef ⇒ r.isStarted
      case _                 ⇒ true
    }
    if (childrenRefs.getByRef(actor).isDefined && started) shallDie(actor)
    actor.asInstanceOf[InternalActorRef].stop()
  }

  var currentMessage: Envelope = _
  var actor: Actor = _
  private var behaviorStack: List[Actor.Receive] = emptyBehaviorStack
  var watching: Set[ActorRef] = emptyActorRefSet
  var watchedBy: Set[ActorRef] = emptyActorRefSet

  @volatile private var _nextNameDoNotCallMeDirectly = 0L
  final protected def randomName(): String = {
    @tailrec def inc(): Long = {
      val current = Unsafe.instance.getLongVolatile(this, nextNameOffset)
      if (Unsafe.instance.compareAndSwapLong(this, nextNameOffset, current, current + 1)) current
      else inc()
    }
    Helpers.base64(inc())
  }

  @volatile private var _mailboxDoNotCallMeDirectly: Mailbox = _ //This must be volatile since it isn't protected by the mailbox status

  /**
   * INTERNAL API
   *
   * Returns a reference to the current mailbox
   */
  @inline final def mailbox: Mailbox = Unsafe.instance.getObjectVolatile(this, mailboxOffset).asInstanceOf[Mailbox]

  /**
   * INTERNAL API
   *
   * replaces the current mailbox using getAndSet semantics
   */
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
  final def resume(): Unit = dispatcher.systemDispatch(this, Resume())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def stop(): Unit = dispatcher.systemDispatch(this, Terminate())

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

  final def children: Iterable[ActorRef] = childrenRefs.children

  /**
   * Impl UntypedActorContext
   */
  final def getChildren(): java.lang.Iterable[ActorRef] =
    scala.collection.JavaConverters.asJavaIterableConverter(children).asJava

  def tell(message: Any, sender: ActorRef): Unit =
    dispatcher.dispatch(this, Envelope(message, if (sender eq null) system.deadLetters else sender, system))

  override def sendSystemMessage(message: SystemMessage): Unit = dispatcher.systemDispatch(this, message)

  final def sender: ActorRef = currentMessage match {
    case null                      ⇒ system.deadLetters
    case msg if msg.sender ne null ⇒ msg.sender
    case _                         ⇒ system.deadLetters
  }

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

    def create(): Unit = if (isNormal) {
      try {
        val created = newActor()
        actor = created
        created.preStart()
        checkReceiveTimeout
        if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(created), "started (" + created + ")"))
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

    def recreate(cause: Throwable): Unit = if (isNormal) {
      try {
        val failedActor = actor
        if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(failedActor), "restarting"))
        if (failedActor ne null) {
          val c = currentMessage //One read only plz
          try {
            if (failedActor.context ne null) failedActor.preRestart(cause, if (c ne null) Some(c.message) else None)
          } finally {
            clearActorFields(failedActor)
          }
        }
        childrenRefs match {
          case ct: TerminatingChildrenContainer ⇒
            setChildrenTerminationReason(Recreation(cause))
            dispatcher suspend this
          case _ ⇒
            doRecreate(cause, failedActor)
        }
      } catch {
        case NonFatal(e) ⇒ throw new ActorInitializationException(self, "exception during creation", e match {
          case i: InstantiationException ⇒ i.getCause
          case other                     ⇒ other
        })
      }
    }

    def suspend(): Unit = if (isNormal) dispatcher suspend this

    def resume(): Unit = if (isNormal) dispatcher resume this

    def addWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
      val watcheeSelf = watchee == self
      val watcherSelf = watcher == self

      if (watcheeSelf && !watcherSelf) {
        if (!watchedBy.contains(watcher)) {
          watchedBy += watcher
          if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(actor), "now monitoring " + watcher))
        }
      } else if (!watcheeSelf && watcherSelf) {
        watch(watchee)
      } else {
        system.eventStream.publish(Warning(self.path.toString, clazz(actor), "BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, self)))
      }
    }

    def remWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
      val watcheeSelf = watchee == self
      val watcherSelf = watcher == self

      if (watcheeSelf && !watcherSelf) {
        if (watchedBy.contains(watcher)) {
          watchedBy -= watcher
          if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(actor), "stopped monitoring " + watcher))
        }
      } else if (!watcheeSelf && watcherSelf) {
        unwatch(watchee)
      } else {
        system.eventStream.publish(Warning(self.path.toString, clazz(actor), "BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, self)))
      }
    }

    def terminate() {
      setReceiveTimeout(None)
      cancelReceiveTimeout

      // stop all children, which will turn childrenRefs into TerminatingChildrenContainer (if there are children)
      children foreach stop

      childrenRefs match {
        case ct: TerminatingChildrenContainer ⇒
          setChildrenTerminationReason(Termination)
          // do not process normal messages while waiting for all children to terminate
          dispatcher suspend this
          if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(actor), "stopping"))
        case _ ⇒ doTerminate()
      }
    }

    def supervise(child: ActorRef): Unit = if (!isTerminating) {
      if (childrenRefs.getByRef(child).isEmpty) addChild(child)
      handleSupervise(child)
      if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(actor), "now supervising " + child))
    }

    try {
      message match {
        case Create()                  ⇒ create()
        case Recreate(cause)           ⇒ recreate(cause)
        case Watch(watchee, watcher)   ⇒ addWatcher(watchee, watcher)
        case Unwatch(watchee, watcher) ⇒ remWatcher(watchee, watcher)
        case Suspend()                 ⇒ suspend()
        case Resume()                  ⇒ resume()
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

  final def handleInvokeFailure(t: Throwable, message: String): Unit = try {
    dispatcher.reportFailure(new LogEventException(Error(t, self.path.toString, clazz(actor), message), t))
    // prevent any further messages to be processed until the actor has been restarted
    dispatcher.suspend(this)
    if (actor ne null) actor.supervisorStrategy.handleSupervisorFailing(self, children)
  } finally {
    t match { // Wrap InterruptedExceptions and rethrow
      case _: InterruptedException ⇒ parent.tell(Failed(new ActorInterruptedException(t)), self); throw t
      case _                       ⇒ parent.tell(Failed(t), self)
    }
  }

  def become(behavior: Actor.Receive, discardOld: Boolean = true): Unit =
    behaviorStack = behavior :: (if (discardOld && behaviorStack.nonEmpty) behaviorStack.tail else behaviorStack)

  /**
   * UntypedActorContext impl
   */
  def become(behavior: Procedure[Any]): Unit = become(behavior, false)

  /*
   * UntypedActorContext impl
   */
  def become(behavior: Procedure[Any], discardOld: Boolean): Unit =
    become({ case msg ⇒ behavior.apply(msg) }: Actor.Receive, discardOld)

  def unbecome(): Unit = {
    val original = behaviorStack
    behaviorStack =
      if (original.isEmpty || original.tail.isEmpty) actor.receive :: emptyBehaviorStack
      else original.tail
  }

  def autoReceiveMessage(msg: Envelope): Unit = {
    if (system.settings.DebugAutoReceive)
      system.eventStream.publish(Debug(self.path.toString, clazz(actor), "received AutoReceiveMessage " + msg))

    msg.message match {
      case Failed(cause)            ⇒ handleFailure(sender, cause)
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
    try {
      try {
        if (a ne null) a.postStop()
      } finally {
        dispatcher.detach(this)
      }
    } finally {
      try {
        parent.sendSystemMessage(ChildTerminated(self))

        if (!watchedBy.isEmpty) {
          val terminated = Terminated(self)(existenceConfirmed = true)
          try {
            watchedBy foreach {
              watcher ⇒
                try watcher.tell(terminated, self) catch {
                  case NonFatal(t) ⇒ system.eventStream.publish(Error(t, self.path.toString, clazz(a), "deathwatch"))
                }
            }
          } finally watchedBy = emptyActorRefSet
        }

        if (!watching.isEmpty) {
          try {
            watching foreach { // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
              case watchee: InternalActorRef ⇒ try watchee.sendSystemMessage(Unwatch(watchee, self)) catch {
                case NonFatal(t) ⇒ system.eventStream.publish(Error(t, self.path.toString, clazz(a), "deathwatch"))
              }
            }
          } finally watching = emptyActorRefSet
        }
        if (system.settings.DebugLifecycle)
          system.eventStream.publish(Debug(self.path.toString, clazz(a), "stopped"))
      } finally {
        behaviorStack = emptyBehaviorStack
        clearActorFields(a)
        actor = null
      }
    }
  }

  private def doRecreate(cause: Throwable, failedActor: Actor): Unit = try {
    // after all killed children have terminated, recreate the rest, then go on to start the new instance
    actor.supervisorStrategy.handleSupervisorRestarted(cause, self, children)
    val freshActor = newActor()
    actor = freshActor // this must happen before postRestart has a chance to fail
    if (freshActor eq failedActor) setActorFields(freshActor, this, self) // If the creator returns the same instance, we need to restore our nulled out fields.

    freshActor.postRestart(cause)
    if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(freshActor), "restarted"))

    dispatcher.resume(this)
  } catch {
    case NonFatal(e) ⇒ try {
      dispatcher.reportFailure(new LogEventException(Error(e, self.path.toString, clazz(actor), "error while creating actor"), e))
      // prevent any further messages to be processed until the actor has been restarted
      dispatcher.suspend(this)
      actor.supervisorStrategy.handleSupervisorFailing(self, children) // FIXME Should this be called on actor or failedActor?
      clearActorFields(actor) // If this fails, we need to ensure that preRestart isn't called.
    } finally {
      parent.tell(Failed(new ActorInitializationException(self, "exception during re-creation", e)), self)
    }
  }

  final def handleFailure(child: ActorRef, cause: Throwable): Unit = childrenRefs.getByRef(child) match {
    case Some(stats) ⇒ if (!actor.supervisorStrategy.handleFailure(this, child, cause, stats, childrenRefs.stats)) throw cause
    case None        ⇒ system.eventStream.publish(Warning(self.path.toString, clazz(actor), "dropping Failed(" + cause + ") from unknown child " + child))
  }

  final def handleChildTerminated(child: ActorRef): Unit = try {
    childrenRefs match {
      case tc @ TerminatingChildrenContainer(_, _, reason) ⇒
        val n = removeChild(child)
        actor.supervisorStrategy.handleChildTerminated(this, child, children)
        if (!n.isInstanceOf[TerminatingChildrenContainer]) reason match {
          case Recreation(cause) ⇒ doRecreate(cause, actor) // doRecreate since this is the continuation of "recreate"
          case Termination       ⇒ doTerminate()
          case _                 ⇒
        }
      case _ ⇒
        removeChild(child)
        actor.supervisorStrategy.handleChildTerminated(this, child, children)
    }
  } catch {
    case NonFatal(e) ⇒
      try {
        dispatcher suspend this
        actor.supervisorStrategy.handleSupervisorFailing(self, children)
      } finally {
        parent.tell(Failed(e), self)
      }
  }

  protected def handleSupervise(child: ActorRef): Unit = child match {
    case r: RepointableActorRef ⇒ r.activate()
    case _                      ⇒
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def restart(cause: Throwable): Unit = dispatcher.systemDispatch(this, Recreate(cause))

  final def checkReceiveTimeout() {
    val recvtimeout = receiveTimeoutData
    if (recvtimeout._1 > 0 && !mailbox.hasMessages) {
      recvtimeout._2.cancel() //Cancel any ongoing future
      //Only reschedule if desired and there are currently no more messages to be processed
      receiveTimeoutData = (recvtimeout._1, system.scheduler.scheduleOnce(Duration(recvtimeout._1, TimeUnit.MILLISECONDS), self, ReceiveTimeout))
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

  private final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass
}

