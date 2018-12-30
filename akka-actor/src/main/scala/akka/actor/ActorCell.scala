/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import java.io.{ NotSerializableException, ObjectOutputStream }
import java.util.concurrent.ThreadLocalRandom

import scala.annotation.{ switch, tailrec }
import scala.collection.immutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import akka.actor.dungeon.ChildrenContainer
import akka.dispatch.{ Envelope, MessageDispatcher }
import akka.dispatch.sysmsg._
import akka.event.Logging.{ Debug, Error, LogEvent }
import akka.japi.Procedure
import akka.util.{ Reflect, unused }
import akka.annotation.InternalApi

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
 * context.actorOf(Props[MyActor])
 * context.actorOf(Props(classOf[MyActor], arg1, arg2), "name")
 *
 * // Java
 * getContext().actorOf(Props.create(MyActor.class));
 * getContext().actorOf(Props.create(MyActor.class, arg1, arg2), "name");
 * }}}
 *
 * Where no name is given explicitly, one will be automatically generated.
 */
trait ActorContext extends ActorRefFactory {

  /**
   * The ActorRef representing this actor
   *
   * This method is thread-safe and can be called from other threads than the ordinary
   * actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def self: ActorRef

  /**
   * Retrieve the Props which were used to create this actor.
   *
   * This method is thread-safe and can be called from other threads than the ordinary
   * actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def props: Props

  /**
   * Gets the current receive timeout.
   * When specified, the receive method should be able to handle a [[akka.actor.ReceiveTimeout]] message.
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def receiveTimeout: Duration

  /**
   * Defines the inactivity timeout after which the sending of a [[akka.actor.ReceiveTimeout]] message is triggered.
   * When specified, the receive function should be able to handle a [[akka.actor.ReceiveTimeout]] message.
   * 1 millisecond is the minimum supported timeout.
   *
   * Please note that the receive timeout might fire and enqueue the `ReceiveTimeout` message right after
   * another message was enqueued; hence it is '''not guaranteed''' that upon reception of the receive
   * timeout there must have been an idle period beforehand as configured via this method.
   *
   * Once set, the receive timeout stays in effect (i.e. continues firing repeatedly after inactivity
   * periods). Pass in `Duration.Undefined` to switch off this feature.
   *
   * Messages marked with [[NotInfluenceReceiveTimeout]] will not reset the timer. This can be useful when
   * `ReceiveTimeout` should be fired by external inactivity but not influenced by internal activity,
   * e.g. scheduled tick messages.
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def setReceiveTimeout(timeout: Duration): Unit

  /**
   * Changes the Actor's behavior to become the new 'Receive' (PartialFunction[Any, Unit]) handler.
   * Replaces the current behavior on the top of the behavior stack.
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def become(behavior: Actor.Receive): Unit = become(behavior, discardOld = true)

  /**
   * Changes the Actor's behavior to become the new 'Receive' (PartialFunction[Any, Unit]) handler.
   * This method acts upon the behavior stack as follows:
   *
   *  - if `discardOld = true` it will replace the top element (i.e. the current behavior)
   *  - if `discardOld = false` it will keep the current behavior and push the given one atop
   *
   * The default of replacing the current behavior on the stack has been chosen to avoid memory
   * leaks in case client code is written without consulting this documentation first (i.e.
   * always pushing new behaviors and never issuing an `unbecome()`)
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def become(behavior: Actor.Receive, discardOld: Boolean): Unit

  /**
   * Reverts the Actor behavior to the previous one on the behavior stack.
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def unbecome(): Unit

  /**
   * Returns the sender 'ActorRef' of the current message.
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def sender(): ActorRef

  /**
   * Returns all supervised children; this method returns a view (i.e. a lazy
   * collection) onto the internal collection of children. Targeted lookups
   * should be using `child` instead for performance reasons:
   *
   * {{{
   * val badLookup = context.children find (_.path.name == "kid")
   * // should better be expressed as:
   * val goodLookup = context.child("kid")
   * }}}
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def children: immutable.Iterable[ActorRef]

  /**
   * Get the child with the given name if it exists.
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def child(name: String): Option[ActorRef]

  /**
   * Returns the dispatcher (MessageDispatcher) that is used for this Actor.
   * Importing this member will place an implicit ExecutionContext in scope.
   *
   * This method is thread-safe and can be called from other threads than the ordinary
   * actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  implicit def dispatcher: ExecutionContextExecutor

  /**
   * The system that the actor belongs to.
   * Importing this member will place an implicit ActorSystem in scope.
   *
   * This method is thread-safe and can be called from other threads than the ordinary
   * actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  implicit def system: ActorSystem

  /**
   * Returns the supervising parent ActorRef.
   *
   * This method is thread-safe and can be called from other threads than the ordinary
   * actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def parent: ActorRef

  /**
   * Registers this actor as a Monitor for the provided ActorRef.
   * This actor will receive a Terminated(subject) message when watched
   * actor is terminated.
   *
   * `watch` is idempotent if it is not mixed with `watchWith`.
   *
   * It will fail with an [[IllegalStateException]] if the same subject was watched before using `watchWith`.
   * To clear the termination message, unwatch first.
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   *
   * @return the provided ActorRef
   */
  def watch(subject: ActorRef): ActorRef

  /**
   * Registers this actor as a Monitor for the provided ActorRef.
   * This actor will receive the specified message when watched
   * actor is terminated.
   *
   * `watchWith` is idempotent if it is called with the same `msg` and not mixed with `watch`.
   *
   * It will fail with an [[IllegalStateException]] if the same subject was watched before using `watch` or `watchWith` with
   * another termination message. To change the termination message, unwatch first.
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   *
   * @return the provided ActorRef
   */
  def watchWith(subject: ActorRef, msg: Any): ActorRef

  /**
   * Unregisters this actor as Monitor for the provided ActorRef.
   * @return the provided ActorRef
   *
   * *Warning*: This method is not thread-safe and must not be accessed from threads other
   * than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and [[scala.concurrent.Future]] callbacks.
   */
  def unwatch(subject: ActorRef): ActorRef

  /**
   * ActorContexts shouldn't be Serializable
   */
  final protected def writeObject(@unused o: ObjectOutputStream): Unit =
    throw new NotSerializableException("ActorContext is not serializable!")
}

/**
 * UntypedActorContext is the UntypedActor equivalent of ActorContext,
 * containing the Java API
 */
@deprecated("Use AbstractActor.ActorContext instead of UntypedActorContext.", since = "2.5.0")
trait UntypedActorContext extends ActorContext {

  /**
   * Returns an unmodifiable Java Collection containing the linked actors,
   * please note that the backing map is thread-safe but not immutable
   */
  def getChildren(): java.lang.Iterable[ActorRef]

  /**
   * Returns a reference to the named child or null if no child with
   * that name exists.
   */
  def getChild(name: String): ActorRef

  /**
   * Changes the Actor's behavior to become the new 'Procedure' handler.
   * Replaces the current behavior on the top of the behavior stack.
   */
  def become(behavior: Procedure[Any]): Unit

  /**
   * Changes the Actor's behavior to become the new 'Procedure' handler.
   * This method acts upon the behavior stack as follows:
   *
   *  - if `discardOld = true` it will replace the top element (i.e. the current behavior)
   *  - if `discardOld = false` it will keep the current behavior and push the given one atop
   *
   * The default of replacing the current behavior on the stack has been chosen to avoid memory
   * leaks in case client code is written without consulting this documentation first (i.e.
   * always pushing new behaviors and never issuing an `unbecome()`)
   */
  def become(behavior: Procedure[Any], discardOld: Boolean): Unit

}

/**
 * INTERNAL API
 */
@InternalApi
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
   * Start the cell: enqueued message must not be processed before this has
   * been called. The usual action is to attach the mailbox to a dispatcher.
   */
  def start(): this.type
  /**
   * Recursively suspend this actor and all its children. Is only allowed to throw Fatal Throwables.
   */
  def suspend(): Unit
  /**
   * Recursively resume this actor and all its children. Is only allowed to throw Fatal Throwables.
   */
  def resume(causedByFailure: Throwable): Unit
  /**
   * Restart this actor (will recursively restart or stop all children). Is only allowed to throw Fatal Throwables.
   */
  def restart(cause: Throwable): Unit
  /**
   * Recursively terminate this actor and all its children. Is only allowed to throw Fatal Throwables.
   */
  def stop(): Unit
  /**
   * Returns “true” if the actor is locally known to be terminated, “false” if
   * alive or uncertain.
   */
  private[akka] def isTerminated: Boolean
  /**
   * The supervisor of this actor.
   */
  def parent: InternalActorRef
  /**
   * All children of this actor, including only reserved-names.
   */
  def childrenRefs: ChildrenContainer
  /**
   * Get the stats for the named child, if that exists.
   */
  def getChildByName(name: String): Option[ChildStats]

  /**
   * Method for looking up a single child beneath this actor.
   * It is racy if called from the outside.
   */
  def getSingleChild(name: String): InternalActorRef

  /**
   * Enqueue a message to be sent to the actor; may or may not actually
   * schedule the actor to run, depending on which type of cell it is.
   * Is only allowed to throw Fatal Throwables.
   */
  def sendMessage(msg: Envelope): Unit

  /**
   * Enqueue a message to be sent to the actor; may or may not actually
   * schedule the actor to run, depending on which type of cell it is.
   * Is only allowed to throw Fatal Throwables.
   */
  final def sendMessage(message: Any, sender: ActorRef): Unit =
    sendMessage(Envelope(message, sender, system))

  /**
   * Enqueue a message to be sent to the actor; may or may not actually
   * schedule the actor to run, depending on which type of cell it is.
   * Is only allowed to throw Fatal Throwables.
   */
  def sendSystemMessage(msg: SystemMessage): Unit
  /**
   * Returns true if the actor is local, i.e. if it is actually scheduled
   * on a Thread in the current JVM when run.
   */
  def isLocal: Boolean
  /**
   * If the actor isLocal, returns whether "user messages" are currently queued,
   * “false” otherwise.
   */
  def hasMessages: Boolean
  /**
   * If the actor isLocal, returns the number of "user messages" currently queued,
   * which may be a costly operation, 0 otherwise.
   */
  def numberOfMessages: Int
  /**
   * The props for this actor cell.
   */
  def props: Props
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
    def isCancelled: Boolean = false
    def cancel(): Boolean = false
  }

  final val emptyBehaviorStack: List[Actor.Receive] = Nil

  final val emptyActorRefSet: Set[ActorRef] = immutable.HashSet.empty

  final val terminatedProps: Props = Props((throw IllegalActorStateException("This Actor has been terminated")): Actor)

  final val undefinedUid = 0

  @tailrec final def newUid(): Int = {
    // Note that this uid is also used as hashCode in ActorRef, so be careful
    // to not break hashing if you change the way uid is generated
    val uid = ThreadLocalRandom.current.nextInt()
    if (uid == undefinedUid) newUid
    else uid
  }

  final def splitNameAndUid(name: String): (String, Int) = {
    val i = name.indexOf('#')
    if (i < 0) (name, undefinedUid)
    else (name.substring(0, i), Integer.valueOf(name.substring(i + 1)))
  }

  final val DefaultState = 0
  final val SuspendedState = 1
  final val SuspendedWaitForChildrenState = 2
}

//ACTORCELL is NOT 64 bytes aligned, unless it is demonstrated to have a large improvement on performance
//vars don't need volatile since it's protected with the mailbox status
//Make sure that they are not read/written outside of a message processing (systemInvoke/invoke)
/**
 * Everything in here is completely Akka PRIVATE. You will not find any
 * supported APIs in this place. This is not the API you were looking
 * for! (waves hand)
 */
private[akka] class ActorCell(
  val system:      ActorSystemImpl,
  val self:        InternalActorRef,
  final val props: Props, // Must be final so that it can be properly cleared in clearActorCellFields
  val dispatcher:  MessageDispatcher,
  val parent:      InternalActorRef)
  extends UntypedActorContext with AbstractActor.ActorContext with Cell
  with dungeon.ReceiveTimeout
  with dungeon.Children
  with dungeon.Dispatch
  with dungeon.DeathWatch
  with dungeon.FaultHandling {

  import ActorCell._

  final def isLocal = true

  final def systemImpl = system
  protected final def guardian = self
  protected final def lookupRoot = self
  final def provider = system.provider

  protected def uid: Int = self.path.uid
  private[this] var _actor: Actor = _
  def actor: Actor = _actor
  protected def actor_=(a: Actor): Unit = _actor = a
  var currentMessage: Envelope = _
  private var behaviorStack: List[Actor.Receive] = emptyBehaviorStack
  private[this] var sysmsgStash: LatestFirstSystemMessageList = SystemMessageList.LNil

  // Java API
  final def getParent() = parent
  // Java API
  final def getSystem() = system
  // Java API
  final override def getDispatcher(): ExecutionContextExecutor = dispatcher
  // Java API
  final override def getSelf(): ActorRef = self
  // Java API
  final override def getProps(): Props = props

  protected def stash(msg: SystemMessage): Unit = {
    assert(msg.unlinked)
    sysmsgStash ::= msg
  }

  private def unstashAll(): LatestFirstSystemMessageList = {
    val unstashed = sysmsgStash
    sysmsgStash = SystemMessageList.LNil
    unstashed
  }

  /*
   * MESSAGE PROCESSING
   */
  //Memory consistency is handled by the Mailbox (reading mailbox status then processing messages, then writing mailbox status
  final def systemInvoke(message: SystemMessage): Unit = {
    /*
     * When recreate/suspend/resume are received while restarting (i.e. between
     * preRestart and postRestart, waiting for children to terminate), these
     * must not be executed immediately, but instead queued and released after
     * finishRecreate returns. This can only ever be triggered by
     * ChildTerminated, and ChildTerminated is not one of the queued message
     * types (hence the overwrite further down). Mailbox sets message.next=null
     * before systemInvoke, so this will only be non-null during such a replay.
     */

    def calculateState: Int =
      if (waitingForChildrenOrNull ne null) SuspendedWaitForChildrenState
      else if (mailbox.isSuspended) SuspendedState
      else DefaultState

    @tailrec def sendAllToDeadLetters(messages: EarliestFirstSystemMessageList): Unit =
      if (messages.nonEmpty) {
        val tail = messages.tail
        val msg = messages.head
        msg.unlink()
        provider.deadLetters ! msg
        sendAllToDeadLetters(tail)
      }

    def shouldStash(m: SystemMessage, state: Int): Boolean =
      (state: @switch) match {
        case DefaultState                  ⇒ false
        case SuspendedState                ⇒ m.isInstanceOf[StashWhenFailed]
        case SuspendedWaitForChildrenState ⇒ m.isInstanceOf[StashWhenWaitingForChildren]
      }

    @tailrec
    def invokeAll(messages: EarliestFirstSystemMessageList, currentState: Int): Unit = {
      val rest = messages.tail
      val message = messages.head
      message.unlink()
      try {
        message match {
          case message: SystemMessage if shouldStash(message, currentState) ⇒ stash(message)
          case f: Failed ⇒ handleFailure(f)
          case DeathWatchNotification(a, ec, at) ⇒ watchedActorTerminated(a, ec, at)
          case Create(failure) ⇒ create(failure)
          case Watch(watchee, watcher) ⇒ addWatcher(watchee, watcher)
          case Unwatch(watchee, watcher) ⇒ remWatcher(watchee, watcher)
          case Recreate(cause) ⇒ faultRecreate(cause)
          case Suspend() ⇒ faultSuspend()
          case Resume(inRespToFailure) ⇒ faultResume(inRespToFailure)
          case Terminate() ⇒ terminate()
          case Supervise(child, async) ⇒ supervise(child, async)
          case NoMessage ⇒ // only here to suppress warning
        }
      } catch handleNonFatalOrInterruptedException { e ⇒
        handleInvokeFailure(Nil, e)
      }
      val newState = calculateState
      // As each state accepts a strict subset of another state, it is enough to unstash if we "walk up" the state
      // chain
      val todo = if (newState < currentState) unstashAll() reverse_::: rest else rest

      if (isTerminated) sendAllToDeadLetters(todo)
      else if (todo.nonEmpty) invokeAll(todo, newState)
    }

    invokeAll(new EarliestFirstSystemMessageList(message), calculateState)
  }

  //Memory consistency is handled by the Mailbox (reading mailbox status then processing messages, then writing mailbox status
  final def invoke(messageHandle: Envelope): Unit = {
    val influenceReceiveTimeout = !messageHandle.message.isInstanceOf[NotInfluenceReceiveTimeout]
    try {
      currentMessage = messageHandle
      if (influenceReceiveTimeout)
        cancelReceiveTimeout()
      messageHandle.message match {
        case _: AutoReceivedMessage ⇒ autoReceiveMessage(messageHandle)
        case msg                    ⇒ receiveMessage(msg)
      }
      currentMessage = null // reset current message after successful invocation
    } catch handleNonFatalOrInterruptedException { e ⇒
      handleInvokeFailure(Nil, e)
    } finally {
      // Schedule or reschedule receive timeout
      checkReceiveTimeout(reschedule = influenceReceiveTimeout)
    }
  }

  def autoReceiveMessage(msg: Envelope): Unit = {
    if (system.settings.DebugAutoReceive)
      publish(Debug(self.path.toString, clazz(actor), "received AutoReceiveMessage " + msg))

    msg.message match {
      case t: Terminated              ⇒ receivedTerminated(t)
      case AddressTerminated(address) ⇒ addressTerminated(address)
      case Kill                       ⇒ throw ActorKilledException("Kill")
      case PoisonPill                 ⇒ self.stop()
      case sel: ActorSelectionMessage ⇒ receiveSelection(sel)
      case Identify(messageId)        ⇒ sender() ! ActorIdentity(messageId, Some(self))
    }
  }

  private def receiveSelection(sel: ActorSelectionMessage): Unit =
    if (sel.elements.isEmpty)
      invoke(Envelope(sel.msg, sender(), system))
    else
      ActorSelection.deliverSelection(self, sender(), sel)

  final def receiveMessage(msg: Any): Unit = actor.aroundReceive(behaviorStack.head, msg)

  /*
   * ACTOR CONTEXT IMPLEMENTATION
   */

  final def sender(): ActorRef = currentMessage match {
    case null                      ⇒ system.deadLetters
    case msg if msg.sender ne null ⇒ msg.sender
    case _                         ⇒ system.deadLetters
  }

  def become(behavior: Actor.Receive, discardOld: Boolean = true): Unit =
    behaviorStack = behavior :: (if (discardOld && behaviorStack.nonEmpty) behaviorStack.tail else behaviorStack)

  def become(behavior: Procedure[Any]): Unit = become(behavior, discardOld = true)

  def become(behavior: Procedure[Any], discardOld: Boolean): Unit =
    become({ case msg ⇒ behavior.apply(msg) }: Actor.Receive, discardOld)

  def unbecome(): Unit = {
    val original = behaviorStack
    behaviorStack =
      if (original.isEmpty || original.tail.isEmpty) actor.receive :: emptyBehaviorStack
      else original.tail
  }

  /*
   * ACTOR INSTANCE HANDLING
   */

  //This method is in charge of setting up the contextStack and create a new instance of the Actor
  protected def newActor(): Actor = {
    contextStack.set(this :: contextStack.get)
    try {
      behaviorStack = emptyBehaviorStack
      val instance = props.newActor()

      if (instance eq null)
        throw ActorInitializationException(self, "Actor instance passed to actorOf can't be 'null'")

      // If no becomes were issued, the actors behavior is its receive method
      behaviorStack = if (behaviorStack.isEmpty) instance.receive :: behaviorStack else behaviorStack
      instance
    } finally {
      val stackAfter = contextStack.get
      if (stackAfter.nonEmpty)
        contextStack.set(if (stackAfter.head eq null) stackAfter.tail.tail else stackAfter.tail) // pop null marker plus our context
    }
  }

  protected def create(failure: Option[ActorInitializationException]): Unit = {
    def clearOutActorIfNonNull(): Unit = {
      if (actor != null) {
        clearActorFields(actor, recreate = false)
        actor = null // ensure that we know that we failed during creation
      }
    }

    failure foreach { throw _ }

    try {
      val created = newActor()
      actor = created
      created.aroundPreStart()
      checkReceiveTimeout()
      if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(created), "started (" + created + ")"))
    } catch {
      case e: InterruptedException ⇒
        clearOutActorIfNonNull()
        Thread.currentThread().interrupt()
        throw ActorInitializationException(self, "interruption during creation", e)
      case NonFatal(e) ⇒
        clearOutActorIfNonNull()
        e match {
          case i: InstantiationException ⇒ throw ActorInitializationException(
            self,
            """exception during creation, this problem is likely to occur because the class of the Actor you tried to create is either,
               a non-static inner class (in which case make it a static inner class or use Props(new ...) or Props( new Creator ... )
               or is missing an appropriate, reachable no-args constructor.
              """, i.getCause)
          case x ⇒ throw ActorInitializationException(self, "exception during creation", x)
        }
    }
  }

  private def supervise(child: ActorRef, async: Boolean): Unit =
    if (!isTerminating) {
      // Supervise is the first thing we get from a new child, so store away the UID for later use in handleFailure()
      initChild(child) match {
        case Some(_) ⇒
          handleSupervise(child, async)
          if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "now supervising " + child))
        case None ⇒ publish(Error(self.path.toString, clazz(actor), "received Supervise from unregistered child " + child + ", this will not end well"))
      }
    }

  // future extension point
  protected def handleSupervise(child: ActorRef, async: Boolean): Unit = child match {
    case r: RepointableActorRef if async ⇒ r.point(catchFailures = true)
    case _                               ⇒
  }

  final protected def clearActorCellFields(cell: ActorCell): Unit = {
    cell.unstashAll()
    if (!Reflect.lookupAndSetField(classOf[ActorCell], cell, "props", ActorCell.terminatedProps))
      throw new IllegalArgumentException("ActorCell has no props field")
  }

  final protected def clearActorFields(actorInstance: Actor, recreate: Boolean): Unit = {
    setActorFields(actorInstance, context = null, self = if (recreate) self else system.deadLetters)
    currentMessage = null
    behaviorStack = emptyBehaviorStack
  }

  final protected def setActorFields(actorInstance: Actor, context: ActorContext, self: ActorRef): Unit =
    if (actorInstance ne null) {
      if (!Reflect.lookupAndSetField(actorInstance.getClass, actorInstance, "context", context)
        || !Reflect.lookupAndSetField(actorInstance.getClass, actorInstance, "self", self))
        throw IllegalActorStateException(actorInstance.getClass + " is not an Actor since it have not mixed in the 'Actor' trait")
    }

  // logging is not the main purpose, and if it fails there’s nothing we can do
  protected final def publish(e: LogEvent): Unit = try system.eventStream.publish(e) catch { case NonFatal(_) ⇒ }

  protected final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass
}
