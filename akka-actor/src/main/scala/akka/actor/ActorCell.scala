/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.io.{ ObjectOutputStream, NotSerializableException }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import akka.actor.dungeon.ChildrenContainer
import akka.actor.dungeon.ChildrenContainer.WaitingForChildren
import akka.dispatch.{ Watch, Unwatch, Terminate, SystemMessage, Suspend, Supervise, Resume, Recreate, NoMessage, MessageDispatcher, Envelope, Create, ChildTerminated }
import akka.event.Logging.{ LogEvent, Debug, Error }
import akka.japi.Procedure
import akka.dispatch.NullMessage
import scala.concurrent.ExecutionContext

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
   * Gets the current receive timeout.
   * When specified, the receive method should be able to handle a 'ReceiveTimeout' message.
   */
  def receiveTimeout: Duration

  /**
   * Defines the inactivity timeout after which the sending of a `ReceiveTimeout` message is triggered.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   * 1 millisecond is the minimum supported timeout.
   *
   * Please note that the receive timeout might fire and enqueue the `ReceiveTimeout` message right after
   * another message was enqueued; hence it is '''not guaranteed''' that upon reception of the receive
   * timeout there must have been an idle period beforehand as configured via this method.
   *
   * Once set, the receive timeout stays in effect (i.e. continues firing repeatedly after inactivity
   * periods). Pass in `Duration.Undefined` to switch off this feature.
   */
  def setReceiveTimeout(timeout: Duration): Unit

  /**
   * Changes the Actor's behavior to become the new 'Receive' (PartialFunction[Any, Unit]) handler.
   * This method acts upon the behavior stack as follows:
   *
   *  - if `discardOld = true` it will replace the top element (i.e. the current behavior)
   *  - if `discardOld = false` it will keep the current behavior and push the given one atop
   *
   * The default of replacing the current behavior has been chosen to avoid memory leaks in
   * case client code is written without consulting this documentation first (i.e. always pushing
   * new closures and never issuing an `unbecome()`)
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
   * Returns all supervised children; this method returns a view (i.e. a lazy
   * collection) onto the internal collection of children. Targeted lookups
   * should be using `actorFor` instead for performance reasons:
   *
   * {{{
   * val badLookup = context.children find (_.path.name == "kid")
   * // should better be expressed as:
   * val goodLookup = context.actorFor("kid")
   * }}}
   */
  def children: immutable.Iterable[ActorRef]

  /**
   * Get the child with the given name if it exists.
   */
  def child(name: String): Option[ActorRef]

  /**
   * Returns the dispatcher (MessageDispatcher) that is used for this Actor.
   * Importing this member will place a implicit MessageDispatcher in scope.
   */
  implicit def dispatcher: ExecutionContext

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
   * This actor will receive a Terminated(watched) message when watched
   * is terminated.
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
   * Returns a reference to the named child or null if no child with
   * that name exists.
   */
  def getChild(name: String): ActorRef

  /**
   * Changes the Actor's behavior to become the new 'Procedure' handler.
   * Replaces the current behavior at the top of the hotswap stack.
   */
  def become(behavior: Procedure[Any]): Unit

  /**
   * Changes the Actor's behavior to become the new 'Procedure' handler.
   * This method acts upon the behavior stack as follows:
   *
   *  - if `discardOld = true` it will replace the top element (i.e. the current behavior)
   *  - if `discardOld = false` it will keep the current behavior and push the given one atop
   *
   * The default of replacing the current behavior has been chosen to avoid memory leaks in
   * case client code is written without consulting this documentation first (i.e. always pushing
   * new closures and never issuing an `unbecome()`)
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
   * Get the stats for the named child, if that exists.
   */
  def getChildByName(name: String): Option[ChildStats]

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

  final val emptyActorRefSet: Set[ActorRef] = immutable.TreeSet.empty
}

//ACTORCELL IS 64bytes and should stay that way unless very good reason not to (machine sympathy, cache line fit)
//vars don't need volatile since it's protected with the mailbox status
//Make sure that they are not read/written outside of a message processing (systemInvoke/invoke)
/**
 * Everything in here is completely Akka PRIVATE. You will not find any
 * supported APIs in this place. This is not the API you were looking
 * for! (waves hand)
 */
private[akka] class ActorCell(
  val system: ActorSystemImpl,
  val self: InternalActorRef,
  val props: Props,
  val parent: InternalActorRef)
  extends UntypedActorContext with Cell
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

  protected var uid: Int = 0
  private[this] var _actor: Actor = _
  def actor: Actor = _actor
  protected def actor_=(a: Actor): Unit = _actor = a
  var currentMessage: Envelope = _
  private var behaviorStack: List[Actor.Receive] = emptyBehaviorStack

  /*
   * MESSAGE PROCESSING
   */
  //Memory consistency is handled by the Mailbox (reading mailbox status then processing messages, then writing mailbox status
  @tailrec final def systemInvoke(message: SystemMessage): Unit = {
    /*
     * When recreate/suspend/resume are received while restarting (i.e. between
     * preRestart and postRestart, waiting for children to terminate), these
     * must not be executed immediately, but instead queued and released after
     * finishRecreate returns. This can only ever be triggered by
     * ChildTerminated, and ChildTerminated is not one of the queued message
     * types (hence the overwrite further down). Mailbox sets message.next=null
     * before systemInvoke, so this will only be non-null during such a replay.
     */
    var todo = message.next
    try {
      message match {
        case Create(uid)               ⇒ create(uid)
        case Watch(watchee, watcher)   ⇒ addWatcher(watchee, watcher)
        case Unwatch(watchee, watcher) ⇒ remWatcher(watchee, watcher)
        case Recreate(cause) ⇒
          waitingForChildrenOrNull match {
            case null                  ⇒ faultRecreate(cause)
            case w: WaitingForChildren ⇒ w.enqueue(message)
          }
        case Suspend() ⇒
          waitingForChildrenOrNull match {
            case null                  ⇒ faultSuspend()
            case w: WaitingForChildren ⇒ w.enqueue(message)
          }
        case Resume(inRespToFailure) ⇒
          waitingForChildrenOrNull match {
            case null                  ⇒ faultResume(inRespToFailure)
            case w: WaitingForChildren ⇒ w.enqueue(message)
          }
        case Terminate()                  ⇒ terminate()
        case Supervise(child, async, uid) ⇒ supervise(child, async, uid)
        case ChildTerminated(child)       ⇒ todo = handleChildTerminated(child)
        case NoMessage                    ⇒ // only here to suppress warning
      }
    } catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒ handleInvokeFailure(Nil, e, "error while processing " + message)
    }
    if (todo != null) systemInvoke(todo)
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
    case e @ (_: InterruptedException | NonFatal(_)) ⇒ handleInvokeFailure(Nil, e, e.getMessage)
  } finally {
    checkReceiveTimeout // Reschedule receive timeout
  }

  def autoReceiveMessage(msg: Envelope): Unit =
    if (msg.message != NullMessage) {
      if (system.settings.DebugAutoReceive)
        publish(Debug(self.path.toString, clazz(actor), "received AutoReceiveMessage " + msg))

      msg.message match {
        case Failed(cause, uid)         ⇒ handleFailure(sender, cause, uid)
        case t: Terminated              ⇒ watchedActorTerminated(t)
        case AddressTerminated(address) ⇒ addressTerminated(address)
        case Kill                       ⇒ throw new ActorKilledException("Kill")
        case PoisonPill                 ⇒ self.stop()
        case SelectParent(m)            ⇒ parent.tell(m, msg.sender)
        case SelectChildName(name, m)   ⇒ getChildByName(name) match { case Some(c: ChildRestartStats) ⇒ c.child.tell(m, msg.sender); case _ ⇒ }
        case SelectChildPattern(p, m)   ⇒ for (c ← children if p.matcher(c.path.name).matches) c.tell(m, msg.sender)
      }
    }

  final def receiveMessage(msg: Any): Unit = behaviorStack.head.applyOrElse(msg, actor.unhandled)

  /*
   * ACTOR CONTEXT IMPLEMENTATION
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
   * ACTOR INSTANCE HANDLING
   */

  //This method is in charge of setting up the contextStack and create a new instance of the Actor
  protected def newActor(): Actor = {
    contextStack.set(this :: contextStack.get)
    try {
      behaviorStack = emptyBehaviorStack
      val instance = props.creator.apply()

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

  protected def create(uid: Int): Unit =
    try {
      this.uid = uid
      val created = newActor()
      actor = created
      created.preStart()
      checkReceiveTimeout
      if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(created), "started (" + created + ")"))
    } catch {
      case NonFatal(e) ⇒
        if (actor != null) {
          clearActorFields(actor)
          actor = null // ensure that we know that we failed during creation
        }
        e match {
          case i: InstantiationException ⇒ throw ActorInitializationException(self,
            """exception during creation, this problem is likely to occur because the class of the Actor you tried to create is either,
               a non-static inner class (in which case make it a static inner class or use Props(new ...) or Props( new UntypedActorFactory ... )
               or is missing an appropriate, reachable no-args constructor.
              """, i.getCause)
          case x ⇒ throw ActorInitializationException(self, "exception during creation", x)
        }
    }

  private def supervise(child: ActorRef, async: Boolean, uid: Int): Unit =
    if (!isTerminating) {
      // Supervise is the first thing we get from a new child, so store away the UID for later use in handleFailure()
      initChild(child) match {
        case Some(crs) ⇒
          crs.uid = uid
          handleSupervise(child, async)
          if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "now supervising " + child))
        case None ⇒ publish(Error(self.path.toString, clazz(actor), "received Supervise from unregistered child " + child + ", this will not end well"))
      }
    }

  // future extension point
  protected def handleSupervise(child: ActorRef, async: Boolean): Unit = child match {
    case r: RepointableActorRef if async ⇒ r.point()
    case _                               ⇒
  }

  final protected def clearActorFields(actorInstance: Actor): Unit = {
    setActorFields(actorInstance, context = null, self = system.deadLetters)
    currentMessage = null
    behaviorStack = emptyBehaviorStack
  }

  final protected def setActorFields(actorInstance: Actor, context: ActorContext, self: ActorRef) {
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
        val parent: Class[_] = clazz.getSuperclass
        if (parent eq null) throw new IllegalActorStateException(actorInstance.getClass + " is not an Actor since it have not mixed in the 'Actor' trait")
        lookupAndSetField(parent, actor, name, value)
      }
    }
    if (actorInstance ne null) {
      lookupAndSetField(actorInstance.getClass, actorInstance, "context", context)
      lookupAndSetField(actorInstance.getClass, actorInstance, "self", self)
    }
  }

  // logging is not the main purpose, and if it fails there’s nothing we can do
  protected final def publish(e: LogEvent): Unit = try system.eventStream.publish(e) catch { case NonFatal(_) ⇒ }

  protected final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass
}

