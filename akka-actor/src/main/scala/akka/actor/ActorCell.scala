/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import scala.annotation.tailrec
import scala.collection.immutable.{ Stack, TreeMap }
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.event.Logging.{ Debug, Warning, Error }
import akka.util.{ Duration, Helpers }
import akka.japi.Procedure
import java.io.{ NotSerializableException, ObjectOutputStream }
import akka.serialization.SerializationExtension
import akka.util.NonFatal
import akka.event.Logging.LogEventException

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

  final protected def writeObject(o: ObjectOutputStream): Unit =
    throw new NotSerializableException("ActorContext is not serializable!")
}

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

  trait SuspendReason
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
  @volatile var parent: InternalActorRef,
  /*no member*/ _receiveTimeout: Option[Duration]) extends UntypedActorContext {

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
  var receiveTimeoutData: (Long, Cancellable) =
    if (_receiveTimeout.isDefined) (_receiveTimeout.get.toMillis, emptyCancellable) else emptyReceiveTimeoutData

  @volatile
  var childrenRefs: ChildrenContainer = EmptyChildrenContainer

  private def isTerminating = childrenRefs match {
    case TerminatingChildrenContainer(_, _, Termination) ⇒ true
    case TerminatedChildrenContainer ⇒ true
    case _ ⇒ false
  }
  private def isNormal = childrenRefs match {
    case TerminatingChildrenContainer(_, _, Termination | _: Recreation) ⇒ false
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

  var currentMessage: Envelope = null

  var actor: Actor = _

  @volatile //This must be volatile since it isn't protected by the mailbox status
  var mailbox: Mailbox = _

  var nextNameSequence: Long = 0

  //Not thread safe, so should only be used inside the actor that inhabits this ActorCell
  final protected def randomName(): String = {
    val n = nextNameSequence
    nextNameSequence = n + 1
    Helpers.base64(n)
  }

  @inline
  final val dispatcher: MessageDispatcher = system.dispatchers.lookup(props.dispatcher)

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
    mailbox = dispatcher.createMailbox(this)
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
  final def resume(): Unit = dispatcher.systemDispatch(this, Resume())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def stop(): Unit = dispatcher.systemDispatch(this, Terminate())

  override final def watch(subject: ActorRef): ActorRef = {
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    dispatcher.systemDispatch(this, Link(subject))
    subject
  }

  override final def unwatch(subject: ActorRef): ActorRef = {
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    dispatcher.systemDispatch(this, Unlink(subject))
    subject
  }

  final def children: Iterable[ActorRef] = childrenRefs.children

  /**
   * Impl UntypedActorContext
   */
  final def getChildren(): java.lang.Iterable[ActorRef] = {
    import scala.collection.JavaConverters.asJavaIterableConverter
    asJavaIterableConverter(children).asJava
  }

  final def tell(message: Any, sender: ActorRef): Unit =
    dispatcher.dispatch(this, Envelope(message, if (sender eq null) system.deadLetters else sender)(system))

  final def sender: ActorRef = currentMessage match {
    case null                      ⇒ system.deadLetters
    case msg if msg.sender ne null ⇒ msg.sender
    case _                         ⇒ system.deadLetters
  }

  //This method is in charge of setting up the contextStack and create a new instance of the Actor
  protected def newActor(): Actor = {
    val stackBefore = contextStack.get
    contextStack.set(stackBefore.push(this))
    try {
      val instance = props.creator()

      if (instance eq null)
        throw ActorInitializationException(self, "Actor instance passed to actorOf can't be 'null'")

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
        if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(created), "started (" + created + ")"))
      } catch {
        case NonFatal(e) ⇒
          try {
            dispatcher.reportFailure(new LogEventException(Error(e, self.path.toString, clazz(actor), "error while creating actor"), e))
            // prevent any further messages to be processed until the actor has been restarted
            dispatcher.suspend(this)
          } finally {
            parent.tell(Failed(ActorInitializationException(self, "exception during creation", e)), self)
          }
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
            clearActorFields()
          }
        }
        childrenRefs match {
          case ct: TerminatingChildrenContainer ⇒
            childrenRefs = ct.copy(reason = Recreation(cause))
            dispatcher suspend this
          case _ ⇒
            doRecreate(cause)
        }
      } catch {
        case NonFatal(e) ⇒ try {
          dispatcher.reportFailure(new LogEventException(Error(e, self.path.toString, clazz(actor), "error while creating actor"), e))
          // prevent any further messages to be processed until the actor has been restarted
          dispatcher.suspend(this)
        } finally {
          parent.tell(Failed(ActorInitializationException(self, "exception during re-creation", e)), self)
        }
      }
    }

    def suspend(): Unit = if (isNormal) dispatcher suspend this

    def resume(): Unit = if (isNormal) dispatcher resume this

    def link(subject: ActorRef): Unit = if (!isTerminating) {
      if (system.deathWatch.subscribe(self, subject)) {
        if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(actor), "now monitoring " + subject))
      }
    }

    def unlink(subject: ActorRef): Unit = if (!isTerminating) {
      if (system.deathWatch.unsubscribe(self, subject)) {
        if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(actor), "stopped monitoring " + subject))
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
          dispatcher suspend this
          if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(actor), "stopping"))
        case _ ⇒ doTerminate()
      }
    }

    def supervise(child: ActorRef): Unit = if (!isTerminating) {
      if (childrenRefs.getByRef(child).isEmpty) childrenRefs = childrenRefs.add(child)
      if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(actor), "now supervising " + child))
    }

    try {
      message match {
        case Create()               ⇒ create()
        case Recreate(cause)        ⇒ recreate(cause)
        case Link(subject)          ⇒ link(subject)
        case Unlink(subject)        ⇒ unlink(subject)
        case Suspend()              ⇒ suspend()
        case Resume()               ⇒ resume()
        case Terminate()            ⇒ terminate()
        case Supervise(child)       ⇒ supervise(child)
        case ChildTerminated(child) ⇒ handleChildTerminated(child)
      }
    } catch {
      case NonFatal(e) ⇒
        dispatcher.reportFailure(new LogEventException(Error(e, self.path.toString, clazz(actor), "error while processing " + message), e))
        throw e
    }
  }

  //Memory consistency is handled by the Mailbox (reading mailbox status then processing messages, then writing mailbox status
  final def invoke(messageHandle: Envelope) {
    try {
      currentMessage = messageHandle
      try {
        try {
          cancelReceiveTimeout() // FIXME: leave this here???
          messageHandle.message match {
            case msg: AutoReceivedMessage ⇒ autoReceiveMessage(messageHandle)
            case msg                      ⇒ actor(msg)
          }
          currentMessage = null // reset current message after successful invocation
        } catch {
          case e: InterruptedException ⇒
            dispatcher.reportFailure(new LogEventException(Error(e, self.path.toString, clazz(actor), e.getMessage), e))
            // prevent any further messages to be processed until the actor has been restarted
            dispatcher.suspend(this)
            // make sure that InterruptedException does not leave this thread
            val ex = ActorInterruptedException(e)
            actor.supervisorStrategy.handleSupervisorFailing(self, children)
            parent.tell(Failed(ex), self)
            throw e //Re-throw InterruptedExceptions as expected
          case NonFatal(e) ⇒
            dispatcher.reportFailure(new LogEventException(Error(e, self.path.toString, clazz(actor), e.getMessage), e))
            // prevent any further messages to be processed until the actor has been restarted
            dispatcher.suspend(this)
            if (actor ne null) actor.supervisorStrategy.handleSupervisorFailing(self, children)
            parent.tell(Failed(e), self)
        } finally {
          checkReceiveTimeout // Reschedule receive timeout
        }
      } catch {
        case NonFatal(e) ⇒
          dispatcher.reportFailure(new LogEventException(Error(e, self.path.toString, clazz(actor), e.getMessage), e))
          throw e
      }
    }
  }

  def become(behavior: Actor.Receive, discardOld: Boolean = true): Unit = {
    if (discardOld) unbecome()
    actor.pushBehavior(behavior)
  }

  /**
   * UntypedActorContext impl
   */
  def become(behavior: Procedure[Any]): Unit = become(behavior, false)

  /*
   * UntypedActorContext impl
   */
  def become(behavior: Procedure[Any], discardOld: Boolean): Unit = {
    def newReceive: Actor.Receive = { case msg ⇒ behavior.apply(msg) }
    become(newReceive, discardOld)
  }

  def unbecome(): Unit = actor.popBehavior()

  def autoReceiveMessage(msg: Envelope) {
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
        system.deathWatch.publish(Terminated(self))
        if (system.settings.DebugLifecycle)
          system.eventStream.publish(Debug(self.path.toString, clazz(actor), "stopped"))
      } finally {
        if (a ne null) a.clearBehaviorStack()
        clearActorFields()
        actor = null
      }
    }
  }

  private def doRecreate(cause: Throwable): Unit = try {
    // after all killed children have terminated, recreate the rest, then go on to start the new instance
    actor.supervisorStrategy.handleSupervisorRestarted(cause, self, children)

    val freshActor = newActor()
    actor = freshActor // this must happen before postRestart has a chance to fail

    freshActor.postRestart(cause)
    if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.path.toString, clazz(freshActor), "restarted"))

    dispatcher.resume(this)
  } catch {
    case NonFatal(e) ⇒ try {
      dispatcher.reportFailure(new LogEventException(Error(e, self.path.toString, clazz(actor), "error while creating actor"), e))
      // prevent any further messages to be processed until the actor has been restarted
      dispatcher.suspend(this)
      actor.supervisorStrategy.handleSupervisorFailing(self, children)
    } finally {
      parent.tell(Failed(ActorInitializationException(self, "exception during re-creation", e)), self)
    }
  }

  final def handleFailure(child: ActorRef, cause: Throwable): Unit = childrenRefs.getByRef(child) match {
    case Some(stats) ⇒ if (!actor.supervisorStrategy.handleFailure(this, child, cause, stats, childrenRefs.stats)) throw cause
    case None        ⇒ system.eventStream.publish(Warning(self.path.toString, clazz(actor), "dropping Failed(" + cause + ") from unknown child " + child))
  }

  final def handleChildTerminated(child: ActorRef): Unit = try {
    childrenRefs match {
      case tc @ TerminatingChildrenContainer(_, _, reason) ⇒
        val n = tc.remove(child)
        childrenRefs = n
        actor.supervisorStrategy.handleChildTerminated(this, child, children)
        if (!n.isInstanceOf[TerminatingChildrenContainer]) reason match {
          case Recreation(cause) ⇒ doRecreate(cause)
          case Termination       ⇒ doTerminate()
          case _                 ⇒
        }
      case _ ⇒
        childrenRefs = childrenRefs.remove(child)
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

  final def cancelReceiveTimeout() {
    //Only cancel if
    if (receiveTimeoutData._2 ne emptyCancellable) {
      receiveTimeoutData._2.cancel()
      receiveTimeoutData = (receiveTimeoutData._1, emptyCancellable)
    }
  }

  final def clearActorFields(): Unit = {
    setActorFields(context = null, self = system.deadLetters)
    currentMessage = null
  }

  final def setActorFields(context: ActorContext, self: ActorRef) {
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
    val a = actor
    if (a ne null) {
      lookupAndSetField(a.getClass, a, "context", context)
      lookupAndSetField(a.getClass, a, "self", self)
    }
  }

  private final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass
}

