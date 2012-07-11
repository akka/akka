/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.io.{ ObjectOutputStream, NotSerializableException }

import scala.annotation.tailrec
import scala.collection.immutable.TreeSet

import ActorCell.{ emptyBehaviorStack, contextStack }
import akka.actor.cell.ChildrenContainer
import akka.dispatch._
import akka.event.Logging.{ LogEvent, Debug }
import akka.japi.Procedure
import akka.util.{ NonFatal, Duration }

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
   * Get the stats for the named child, if that exists.
   */
  def getChildByName(name: String): Option[ChildRestartStats]
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

  final val emptyBehaviorStack: List[Actor.Receive] = Nil

  final val emptyActorRefSet: Set[ActorRef] = TreeSet.empty
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
  @volatile var parent: InternalActorRef)
  extends UntypedActorContext with Cell
  with cell.ReceiveTimeout
  with cell.Children
  with cell.Dispatch
  with cell.DeathWatch
  with cell.FaultHandling {

  import ActorCell._

  final def isLocal = true

  final def systemImpl = system
  protected final def guardian = self
  protected final def lookupRoot = self
  final def provider = system.provider

  var actor: Actor = _
  var currentMessage: Envelope = _
  private var behaviorStack: List[Actor.Receive] = emptyBehaviorStack

  /*
   * MESSAGE PROCESSING
   */

  //Memory consistency is handled by the Mailbox (reading mailbox status then processing messages, then writing mailbox status
  final def systemInvoke(message: SystemMessage): Unit = try {
    message match {
      case Create()                  ⇒ create()
      case Recreate(cause)           ⇒ faultRecreate(cause)
      case Watch(watchee, watcher)   ⇒ addWatcher(watchee, watcher)
      case Unwatch(watchee, watcher) ⇒ remWatcher(watchee, watcher)
      case Suspend()                 ⇒ faultSuspend()
      case Resume(inRespToFailure)   ⇒ faultResume(inRespToFailure)
      case Terminate()               ⇒ terminate()
      case Supervise(child)          ⇒ supervise(child)
      case ChildTerminated(child)    ⇒ handleChildTerminated(child)
      case NoMessage                 ⇒ // only here to suppress warning
    }
  } catch {
    case e @ (_: InterruptedException | NonFatal(_)) ⇒ handleInvokeFailure(e, "error while processing " + message)
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

  def autoReceiveMessage(msg: Envelope): Unit = {
    if (system.settings.DebugAutoReceive)
      publish(Debug(self.path.toString, clazz(actor), "received AutoReceiveMessage " + msg))

    msg.message match {
      case Failed(cause)            ⇒ handleFailure(sender, cause)
      case t: Terminated            ⇒ watchedActorTerminated(t.actor); receiveMessage(t)
      case Kill                     ⇒ throw new ActorKilledException("Kill")
      case PoisonPill               ⇒ self.stop()
      case SelectParent(m)          ⇒ parent.tell(m, msg.sender)
      case SelectChildName(name, m) ⇒ for (c ← getChildByName(name)) c.child.tell(m, msg.sender)
      case SelectChildPattern(p, m) ⇒ for (c ← children if p.matcher(c.path.name).matches) c.tell(m, msg.sender)
    }
  }

  final def receiveMessage(msg: Any): Unit = {
    //FIXME replace with behaviorStack.head.applyOrElse(msg, unhandled) + "-optimize"
    val head = behaviorStack.head
    if (head.isDefinedAt(msg)) head.apply(msg) else actor.unhandled(msg)
  }

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

  private def create(): Unit = if (isNormal) {
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

  private def supervise(child: ActorRef): Unit = if (!isTerminating) {
    addChild(child)
    handleSupervise(child)
    if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "now supervising " + child))
  }

  // future extension point
  protected def handleSupervise(child: ActorRef): Unit = child match {
    case r: RepointableActorRef ⇒ r.activate()
    case _                      ⇒
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
  protected final def publish(e: LogEvent): Unit = try system.eventStream.publish(e) catch { case NonFatal(_) ⇒ }

  protected final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass
}

