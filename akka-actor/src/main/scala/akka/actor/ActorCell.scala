/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import scala.annotation.tailrec
import scala.collection.immutable.{ Stack, TreeMap }
import java.util.concurrent.TimeUnit
import akka.event.Logging.{ Debug, Warning, Error }
import akka.util.{ Duration, Helpers }

/**
 * The actor context - the view of the actor cell from the actor.
 * Exposes contextual information for the actor and the current message.
 * TODO: everything here for current compatibility - could be limited more
 */
trait ActorContext extends ActorRefFactory {

  def self: ActorRef

  def receiveTimeout: Option[Long]

  def receiveTimeout_=(timeout: Option[Long]): Unit

  def become(behavior: Actor.Receive, discardOld: Boolean): Unit

  def hotswap: Stack[PartialFunction[Any, Unit]]

  def unbecome(): Unit

  def currentMessage: Envelope

  def currentMessage_=(invocation: Envelope): Unit

  def sender: ActorRef

  def children: Iterable[ActorRef]

  def dispatcher: MessageDispatcher

  def handleFailure(child: ActorRef, cause: Throwable): Unit

  def handleChildTerminated(child: ActorRef): Unit

  def system: ActorSystem

  def parent: ActorRef
}

private[akka] object ActorCell {
  val contextStack = new ThreadLocal[Stack[ActorContext]] {
    override def initialValue = Stack[ActorContext]()
  }

  val emptyChildrenRefs = TreeMap[String, ChildRestartStats]()

  final val emptyCancellable: Cancellable = new Cancellable {
    def isCancelled = false
    def cancel() {}
  }

  final val emptyReceiveTimeoutData: (Long, Cancellable) = (-1, emptyCancellable)
}

//ACTORCELL IS 64bytes and should stay that way unless very good reason not to (machine sympathy, cache line fit)
//vars don't need volatile since it's protected with the mailbox status
//Make sure that they are not read/written outside of a message processing (systemInvoke/invoke)
private[akka] class ActorCell(
  val system: ActorSystemImpl,
  val self: ActorRef with ScalaActorRef,
  val props: Props,
  val parent: ActorRef,
  /*no member*/ _receiveTimeout: Option[Long],
  var hotswap: Stack[PartialFunction[Any, Unit]]) extends ActorContext {

  import ActorCell._

  def systemImpl = system

  protected final def guardian = self

  final def provider = system.provider

  override def receiveTimeout: Option[Long] = if (receiveTimeoutData._1 > 0) Some(receiveTimeoutData._1) else None

  override def receiveTimeout_=(timeout: Option[Long]): Unit = {
    val timeoutMs = if (timeout.isDefined && timeout.get > 0) timeout.get else -1
    receiveTimeoutData = (timeoutMs, receiveTimeoutData._2)
  }

  var receiveTimeoutData: (Long, Cancellable) =
    if (_receiveTimeout.isDefined) (_receiveTimeout.get, emptyCancellable) else emptyReceiveTimeoutData

  var childrenRefs: TreeMap[String, ChildRestartStats] = emptyChildrenRefs

  var currentMessage: Envelope = null

  var actor: Actor = _

  var stopping = false

  @volatile //This must be volatile since it isn't protected by the mailbox status
  var mailbox: Mailbox = _

  var nextNameSequence: Long = 0

  //Not thread safe, so should only be used inside the actor that inhabits this ActorCell
  override protected def randomName(): String = {
    val n = nextNameSequence + 1
    nextNameSequence = n
    Helpers.base64(n)
  }

  @inline
  final def dispatcher: MessageDispatcher = if (props.dispatcher == Props.defaultDispatcher) system.dispatcher else props.dispatcher

  final def isTerminated: Boolean = mailbox.isClosed

  final def start(): Unit = {
    mailbox = dispatcher.createMailbox(this)

    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    parent.sendSystemMessage(akka.dispatch.Supervise(self))

    dispatcher.attach(this)
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def suspend(): Unit = dispatcher.systemDispatch(this, Suspend())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def resume(): Unit = dispatcher.systemDispatch(this, Resume())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  private[akka] def stop(): Unit = dispatcher.systemDispatch(this, Terminate())

  final def startsWatching(subject: ActorRef): ActorRef = {
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    dispatcher.systemDispatch(this, Link(subject))
    subject
  }

  final def stopsWatching(subject: ActorRef): ActorRef = {
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    dispatcher.systemDispatch(this, Unlink(subject))
    subject
  }

  final def children: Iterable[ActorRef] = childrenRefs.values.view.map(_.child)

  final def getChild(name: String): Option[ActorRef] =
    if (isTerminated) None else childrenRefs.get(name).map(_.child)

  final def tell(message: Any, sender: ActorRef): Unit =
    dispatcher.dispatch(this, Envelope(message, if (sender eq null) system.deadLetters else sender))

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

    def create(): Unit = try {
      val created = newActor()
      actor = created
      created.preStart()
      checkReceiveTimeout
      if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.toString, "started (" + actor + ")"))
    } catch {
      case e ⇒
        try {
          system.eventStream.publish(Error(e, self.toString, "error while creating actor"))
          // prevent any further messages to be processed until the actor has been restarted
          dispatcher.suspend(this)
        } finally {
          parent.tell(Failed(ActorInitializationException(self, "exception during creation", e)), self)
        }
    }

    def recreate(cause: Throwable): Unit = try {
      val failedActor = actor
      if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.toString, "restarting"))
      val freshActor = newActor()
      if (failedActor ne null) {
        val c = currentMessage //One read only plz
        try {
          failedActor.preRestart(cause, if (c ne null) Some(c.message) else None)
        } finally {
          clearActorFields()
          currentMessage = null
          actor = null
        }
      }
      actor = freshActor // assign it here so if preStart fails, we can null out the sef-refs next call
      freshActor.postRestart(cause)
      if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.toString, "restarted"))

      dispatcher.resume(this) //FIXME should this be moved down?

      props.faultHandler.handleSupervisorRestarted(cause, self, children)
    } catch {
      case e ⇒ try {
        system.eventStream.publish(Error(e, self.toString, "error while creating actor"))
        // prevent any further messages to be processed until the actor has been restarted
        dispatcher.suspend(this)
      } finally {
        parent.tell(Failed(ActorInitializationException(self, "exception during re-creation", e)), self)
      }
    }

    def suspend(): Unit = dispatcher suspend this

    def resume(): Unit = dispatcher resume this

    def terminate() {
      receiveTimeout = None
      cancelReceiveTimeout

      val c = children
      if (c.isEmpty) doTerminate()
      else {
        if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.toString, "stopping"))
        for (child ← c) child.stop()
        stopping = true
      }
    }

    def supervise(child: ActorRef): Unit = {
      val stat = childrenRefs.get(child.name)
      if (stat.isDefined) {
        if (stat.get.child == child)
          system.eventStream.publish(Warning(self.toString, "Already supervising " + child))
        else
          system.eventStream.publish(Warning(self.toString, "Already supervising other child with same name '" + child.name + "', old: " + stat.get + " new: " + child))
      } else {
        childrenRefs = childrenRefs.updated(child.name, ChildRestartStats(child))
        if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.toString, "now supervising " + child))
      }
    }

    try {
      if (stopping) message match {
        case Terminate() ⇒ terminate() // to allow retry
        case _           ⇒
      }
      else message match {
        case Create()        ⇒ create()
        case Recreate(cause) ⇒ recreate(cause)
        case Link(subject) ⇒
          system.deathWatch.subscribe(self, subject)
          if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.toString, "now monitoring " + subject))
        case Unlink(subject) ⇒
          system.deathWatch.unsubscribe(self, subject)
          if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.toString, "stopped monitoring " + subject))
        case Suspend()        ⇒ suspend()
        case Resume()         ⇒ resume()
        case Terminate()      ⇒ terminate()
        case Supervise(child) ⇒ supervise(child)
      }
    } catch {
      case e ⇒ //Should we really catch everything here?
        system.eventStream.publish(Error(e, self.toString, "error while processing " + message))
        //TODO FIXME How should problems here be handled?
        throw e
    }
  }

  //Memory consistency is handled by the Mailbox (reading mailbox status then processing messages, then writing mailbox status
  final def invoke(messageHandle: Envelope) {
    try {
      currentMessage = messageHandle
      try {
        try {
          cancelReceiveTimeout() // FIXME: leave this here?
          messageHandle.message match {
            case msg: AutoReceivedMessage ⇒ autoReceiveMessage(messageHandle)
            case msg if stopping ⇒ // receiving Terminated in response to stopping children is too common to generate noise
              if (!msg.isInstanceOf[Terminated]) system.deadLetterMailbox.enqueue(self, messageHandle)
            case msg ⇒ actor(msg)
          }
          currentMessage = null // reset current message after successful invocation
        } catch {
          case e ⇒
            system.eventStream.publish(Error(e, self.toString, e.getMessage))

            // prevent any further messages to be processed until the actor has been restarted
            dispatcher.suspend(this)

            // make sure that InterruptedException does not leave this thread
            if (e.isInstanceOf[InterruptedException]) {
              val ex = ActorInterruptedException(e)
              props.faultHandler.handleSupervisorFailing(self, children)
              parent.tell(Failed(ex), self)
              throw e //Re-throw InterruptedExceptions as expected
            } else {
              props.faultHandler.handleSupervisorFailing(self, children)
              parent.tell(Failed(e), self)
            }
        } finally {
          checkReceiveTimeout // Reschedule receive timeout
        }
      } catch {
        case e ⇒
          system.eventStream.publish(Error(e, self.toString, e.getMessage))
          throw e
      }
    }
  }

  def become(behavior: Actor.Receive, discardOld: Boolean = true) {
    if (discardOld) unbecome()
    hotswap = hotswap.push(behavior)
  }

  def unbecome() {
    val h = hotswap
    if (h.nonEmpty) hotswap = h.pop
  }

  def autoReceiveMessage(msg: Envelope) {
    if (system.settings.DebugAutoReceive) system.eventStream.publish(Debug(self.toString, "received AutoReceiveMessage " + msg))

    if (stopping) msg.message match {
      case ChildTerminated ⇒ handleChildTerminated(sender)
      case _               ⇒ system.deadLetterMailbox.enqueue(self, msg)
    }
    else msg.message match {
      case HotSwap(code, discardOld) ⇒ become(code(self), discardOld)
      case RevertHotSwap             ⇒ unbecome()
      case Failed(cause)             ⇒ handleFailure(sender, cause)
      case ChildTerminated           ⇒ handleChildTerminated(sender)
      case Kill                      ⇒ throw new ActorKilledException("Kill")
      case PoisonPill                ⇒ self.stop()
    }
  }

  private def doTerminate() {
    if (!system.provider.evict(self.path.toString))
      system.eventStream.publish(Warning(self.toString, "evict of " + self.path.toString + " failed"))

    dispatcher.detach(this)

    try {
      val a = actor
      if (a ne null) a.postStop()
    } finally {
      try {
        parent.tell(ChildTerminated, self)
        system.deathWatch.publish(Terminated(self))
        if (system.settings.DebugLifecycle) system.eventStream.publish(Debug(self.toString, "stopped"))
      } finally {
        currentMessage = null
        clearActorFields()
      }
    }
  }

  final def handleFailure(child: ActorRef, cause: Throwable): Unit = childrenRefs.get(child.name) match {
    case Some(stats) if stats.child == child ⇒ if (!props.faultHandler.handleFailure(child, cause, stats, childrenRefs.values)) throw cause
    case Some(stats)                         ⇒ system.eventStream.publish(Warning(self.toString, "dropping Failed(" + cause + ") from unknown child " + child + " matching names but not the same, was: " + stats.child))
    case None                                ⇒ system.eventStream.publish(Warning(self.toString, "dropping Failed(" + cause + ") from unknown child " + child))
  }

  final def handleChildTerminated(child: ActorRef): Unit = {
    childrenRefs -= child.name
    props.faultHandler.handleChildTerminated(child, children)
    if (stopping && childrenRefs.isEmpty) doTerminate()
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def restart(cause: Throwable): Unit = dispatcher.systemDispatch(this, Recreate(cause))

  final def checkReceiveTimeout() {
    val recvtimeout = receiveTimeoutData
    if (recvtimeout._1 > 0 && dispatcher.mailboxIsEmpty(this)) {
      recvtimeout._2.cancel() //Cancel any ongoing future
      //Only reschedule if desired and there are currently no more messages to be processed
      receiveTimeoutData = (recvtimeout._1, system.scheduler.scheduleOnce(self, ReceiveTimeout, Duration(recvtimeout._1, TimeUnit.MILLISECONDS)))
    } else cancelReceiveTimeout()

  }

  final def cancelReceiveTimeout() {
    //Only cancel if
    if (receiveTimeoutData._2 ne emptyCancellable) {
      receiveTimeoutData._2.cancel()
      receiveTimeoutData = (receiveTimeoutData._1, emptyCancellable)
    }
  }

  final def clearActorFields(): Unit = setActorFields(context = null, self = null)

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
}
