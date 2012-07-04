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
import collection.immutable.{ TreeSet, Stack, TreeMap }
import akka.util.{ Unsafe, Duration, Helpers, NonFatal }

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
 * Everything in here is completely Akka PRIVATE. You will not find any
 * supported APIs in this place. This is not the API you were looking
 * for! (waves hand)
 */
private[akka] object ActorCell {
  val contextStack = new ThreadLocal[Stack[ActorContext]] {
    override def initialValue = Stack[ActorContext]()
  }

  final val emptyCancellable: Cancellable = new Cancellable {
    def isCancelled = false
    def cancel() {}
  }

  final val emptyReceiveTimeoutData: (Long, Cancellable) = (-1, emptyCancellable)

  final val behaviorStackPlaceHolder: Stack[Actor.Receive] = Stack.empty.push(Actor.emptyBehavior)

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
  }

  trait EmptyChildrenContainer extends ChildrenContainer {
    val emptyStats = TreeMap.empty[String, ChildRestartStats]
    def add(child: ActorRef): ChildrenContainer =
      new NormalChildrenContainer(emptyStats.updated(child.path.name, ChildRestartStats(child)))
    def remove(child: ActorRef): ChildrenContainer = this
    def getByName(name: String): Option[ChildRestartStats] = None
    def getByRef(actor: ActorRef): Option[ChildRestartStats] = None
    def children: Iterable[ActorRef] = Nil
    def stats: Iterable[ChildRestartStats] = Nil
    def shallDie(actor: ActorRef): ChildrenContainer = this
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
  }

  /**
   * Normal children container: we do have at least one child, but none of our
   * children are currently terminating (which is the time period between
   * calling context.stop(child) and processing the ChildTerminated() system
   * message).
   */
  class NormalChildrenContainer(c: TreeMap[String, ChildRestartStats]) extends ChildrenContainer {

    def add(child: ActorRef): ChildrenContainer = new NormalChildrenContainer(c.updated(child.path.name, ChildRestartStats(child)))

    def remove(child: ActorRef): ChildrenContainer = NormalChildrenContainer(c - child.path.name)

    def getByName(name: String): Option[ChildRestartStats] = c get name

    def getByRef(actor: ActorRef): Option[ChildRestartStats] = c get actor.path.name match {
      case c @ Some(crs) if (crs.child == actor) ⇒ c
      case _                                     ⇒ None
    }

    def children: Iterable[ActorRef] = c.values.view.map(_.child)

    def stats: Iterable[ChildRestartStats] = c.values

    def shallDie(actor: ActorRef): ChildrenContainer = TerminatingChildrenContainer(c, Set(actor), UserRequest)

    override def toString =
      if (c.size > 20) c.size + " children"
      else c.mkString("children:\n    ", "\n    ", "")
  }

  object NormalChildrenContainer {
    def apply(c: TreeMap[String, ChildRestartStats]): ChildrenContainer =
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
  case class TerminatingChildrenContainer(c: TreeMap[String, ChildRestartStats], toDie: Set[ActorRef], reason: SuspendReason)
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

    def getByName(name: String): Option[ChildRestartStats] = c get name

    def getByRef(actor: ActorRef): Option[ChildRestartStats] = c get actor.path.name match {
      case c @ Some(crs) if (crs.child == actor) ⇒ c
      case _                                     ⇒ None
    }

    def children: Iterable[ActorRef] = c.values.view.map(_.child)

    def stats: Iterable[ChildRestartStats] = c.values

    def shallDie(actor: ActorRef): ChildrenContainer = copy(toDie = toDie + actor)

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
  @volatile var parent: InternalActorRef) extends UntypedActorContext {
  import AbstractActorCell.mailboxOffset
  import ActorCell._

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
  var childrenRefs: ChildrenContainer = EmptyChildrenContainer

  private def isTerminating = childrenRefs match {
    case TerminatingChildrenContainer(_, _, Termination) ⇒ true
    case TerminatedChildrenContainer ⇒ true
    case _ ⇒ false
  }
  private def isNormal = childrenRefs match {
    case TerminatingChildrenContainer(_, _, Termination | _: Recreation) ⇒ false
    case TerminatedChildrenContainer ⇒ false
    case _ ⇒ true
  }

  private def _actorOf(props: Props, name: String): ActorRef = {
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
    // in case we are currently terminating, swallow creation requests and return EmptyLocalActorRef
    if (isTerminating) provider.actorFor(self, Seq(name))
    else {
      val actor = provider.actorOf(systemImpl, props, self, self.path / name, false, None, true)
      childrenRefs = childrenRefs.add(actor)
      actor
    }
  }

  def actorOf(props: Props): ActorRef = _actorOf(props, randomName())

  def actorOf(props: Props, name: String): ActorRef = {
    import ActorPath.ElementRegex
    name match {
      case null           ⇒ throw new InvalidActorNameException("actor name must not be null")
      case ""             ⇒ throw new InvalidActorNameException("actor name must not be empty")
      case ElementRegex() ⇒ // this is fine
      case _              ⇒ throw new InvalidActorNameException("illegal actor name '" + name + "', must conform to " + ElementRegex)
    }
    childrenRefs.getByName(name) match {
      case None ⇒ _actorOf(props, name)
      case _    ⇒ throw new InvalidActorNameException("actor name " + name + " is not unique!")
    }
  }

  final def stop(actor: ActorRef): Unit = {
    if (childrenRefs.getByRef(actor).isDefined) childrenRefs = childrenRefs.shallDie(actor)
    actor.asInstanceOf[InternalActorRef].stop()
  }

  var currentMessage: Envelope = _
  var actor: Actor = _
  private var behaviorStack: Stack[Actor.Receive] = Stack.empty
  @volatile var _mailboxDoNotCallMeDirectly: Mailbox = _ //This must be volatile since it isn't protected by the mailbox status
  var nextNameSequence: Long = 0
  var watching: Set[ActorRef] = emptyActorRefSet
  var watchedBy: Set[ActorRef] = emptyActorRefSet

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

  //Not thread safe, so should only be used inside the actor that inhabits this ActorCell
  final protected def randomName(): String = {
    val n = nextNameSequence
    nextNameSequence = n + 1
    Helpers.base64(n)
  }

  @inline
  final val dispatcher: MessageDispatcher = system.dispatchers.lookup(props.dispatcher)

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

  /**
   * UntypedActorContext impl
   */
  final def getDispatcher(): MessageDispatcher = dispatcher

  final def isTerminated: Boolean = mailbox.isClosed

  final def start(): Unit = {
    /*
     * Create the mailbox and enqueue the Create() message to ensure that
     * this is processed before anything else.
     */
    swapMailbox(dispatcher.createMailbox(this))
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    mailbox.systemEnqueue(self, Create())

    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    parent.sendSystemMessage(akka.dispatch.Supervise(self))

    // This call is expected to start off the actor by scheduling its mailbox.
    dispatcher.attach(this)
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def suspend(): Unit = dispatcher.systemDispatch(this, Suspend())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def resume(inResponseToFailure: Boolean): Unit = dispatcher.systemDispatch(this, Resume(inResponseToFailure))

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

  final def children: Iterable[ActorRef] = childrenRefs.children

  /**
   * Impl UntypedActorContext
   */
  final def getChildren(): java.lang.Iterable[ActorRef] =
    scala.collection.JavaConverters.asJavaIterableConverter(children).asJava

  final def tell(message: Any, sender: ActorRef): Unit =
    dispatcher.dispatch(this, Envelope(message, if (sender eq null) system.deadLetters else sender)(system))

  final def sender: ActorRef = currentMessage match {
    case null                      ⇒ system.deadLetters
    case msg if msg.sender ne null ⇒ msg.sender
    case _                         ⇒ system.deadLetters
  }

  //This method is in charge of setting up the contextStack and create a new instance of the Actor
  protected def newActor(): Actor = {
    contextStack.set(contextStack.get.push(this))
    try {
      import ActorCell.behaviorStackPlaceHolder

      behaviorStack = behaviorStackPlaceHolder
      val instance = props.creator.apply()

      if (instance eq null)
        throw new ActorInitializationException(self, "Actor instance passed to actorOf can't be 'null'")

      behaviorStack = behaviorStack match {
        case `behaviorStackPlaceHolder` ⇒ Stack.empty.push(instance.receive)
        case newBehaviors               ⇒ Stack.empty.push(instance.receive).pushAll(newBehaviors.reverse.drop(1))
      }
      instance
    } finally {
      val stackAfter = contextStack.get
      if (stackAfter.nonEmpty)
        contextStack.set(if (stackAfter.head eq null) stackAfter.pop.pop else stackAfter.pop) // pop null marker plus our context
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
      if (isNormal) {
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
            childrenRefs = ct.copy(reason = Recreation(cause))
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
      children foreach (_.asInstanceOf[InternalActorRef].suspend())
    }

    def doResume(inResponseToFailure: Boolean): Unit = {
      // done always to keep that suspend counter balanced
      // must happen “atomically”
      try resumeNonRecursive()
      finally if (inResponseToFailure) setNotFailed()
      children foreach (_.asInstanceOf[InternalActorRef].resume(inResponseToFailure = false))
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
          childrenRefs = ct.copy(reason = Termination)
          // do not process normal messages while waiting for all children to terminate
          suspendNonRecursive()
          if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "stopping"))
        case _ ⇒ doTerminate()
      }
    }

    def supervise(child: ActorRef): Unit = if (!isTerminating) {
      if (childrenRefs.getByRef(child).isEmpty) childrenRefs = childrenRefs.add(child)
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
        case NoMessage                 ⇒ // to shut up the exhaustiveness warning
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
    try {
      dispatcher.reportFailure(new LogEventException(Error(t, self.path.toString, clazz(actor), message), t))
    } catch {
      case NonFatal(_) ⇒ // no sense logging if logging does not work
    }
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
      childrenRefs.stats collect {
        case ChildRestartStats(child, _, _) if !(skip contains child) ⇒ child
      } foreach (_.asInstanceOf[InternalActorRef].suspend())
      // tell supervisor
      t match { // Wrap InterruptedExceptions and rethrow
        case _: InterruptedException ⇒ parent.tell(Failed(new ActorInterruptedException(t)), self); throw t
        case _                       ⇒ parent.tell(Failed(t), self)
      }
    }
  }

  def become(behavior: Actor.Receive, discardOld: Boolean = true): Unit = {
    if (discardOld) unbecome()
    behaviorStack = behaviorStack.push(behavior)
  }

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
    val popped = original.pop
    behaviorStack = if (popped.isEmpty) original else popped
  }

  def autoReceiveMessage(msg: Envelope): Unit = {
    if (system.settings.DebugAutoReceive)
      publish(Debug(self.path.toString, clazz(actor), "received AutoReceiveMessage " + msg))

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
      behaviorStack = behaviorStackPlaceHolder
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
      case tc @ TerminatingChildrenContainer(_, _, reason) ⇒
        val n = tc.remove(child)
        childrenRefs = n
        actor.supervisorStrategy.handleChildTerminated(this, child, children)
        if (!n.isInstanceOf[TerminatingChildrenContainer]) reason match {
          case Recreation(cause) ⇒ doRecreate(cause, actor) // doRecreate since this is the continuation of "recreate"
          case Termination       ⇒ doTerminate()
          case _                 ⇒
        }
      case _ ⇒
        childrenRefs = childrenRefs.remove(child)
        actor.supervisorStrategy.handleChildTerminated(this, child, children)
    }
  } catch {
    case NonFatal(e) ⇒ handleInvokeFailure(e, "handleChildTerminated failed")
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

  // logging is not the main purpose, and if it fails there’s nothing we can do
  private final def publish(e: LogEvent): Unit = try system.eventStream.publish(e) catch { case NonFatal(_) ⇒ }

  private final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass
}

