/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import scala.annotation.tailrec
import scala.collection.immutable.{ Stack, TreeMap }
import java.util.concurrent.TimeUnit
import akka.event.Logging.{ Debug, Warning, Error }

/**
 * The actor context - the view of the actor cell from the actor.
 * Exposes contextual information for the actor and the current message.
 * TODO: everything here for current compatibility - could be limited more
 */
trait ActorContext extends ActorRefFactory with TypedActorFactory {

  def self: ActorRef with ScalaActorRef

  def hasMessages: Boolean

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

  def app: ActorSystem

  def parent: ActorRef
}

private[akka] object ActorCell {
  val contextStack = new ThreadLocal[Stack[ActorContext]] {
    override def initialValue = Stack[ActorContext]()
  }

  val emptyChildrenRefs = TreeMap[String, ActorRef]()

  val emptyChildrenStats = TreeMap[ActorRef, ChildRestartStats]()
}

//vars don't need volatile since it's protected with the mailbox status
//Make sure that they are not read/written outside of a message processing (systemInvoke/invoke)
private[akka] class ActorCell(
  val app: ActorSystem,
  val self: ActorRef with ScalaActorRef,
  val props: Props,
  val parent: ActorRef,
  var receiveTimeout: Option[Long],
  var hotswap: Stack[PartialFunction[Any, Unit]]) extends ActorContext {

  import ActorCell._

  protected final def guardian = self

  protected def typedActor = app.typedActor

  final def provider = app.provider

  var futureTimeout: Option[Cancellable] = None

  var childrenRefs = emptyChildrenRefs

  var childrenStats = emptyChildrenStats

  var currentMessage: Envelope = null

  var actor: Actor = _

  var stopping = false

  @inline
  final def dispatcher: MessageDispatcher = if (props.dispatcher == Props.defaultDispatcher) app.dispatcher else props.dispatcher

  final def isShutdown: Boolean = mailbox.isClosed

  @volatile //This must be volatile since it isn't protected by the mailbox status
  var mailbox: Mailbox = _

  def hasMessages: Boolean = mailbox.hasMessages

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

  final def startsMonitoring(subject: ActorRef): ActorRef = {
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    dispatcher.systemDispatch(this, Link(subject))
    subject
  }

  final def stopsMonitoring(subject: ActorRef): ActorRef = {
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    dispatcher.systemDispatch(this, Unlink(subject))
    subject
  }

  final def children: Iterable[ActorRef] = childrenStats.keys

  final def getChild(name: String): Option[ActorRef] = {
    val isClosed = mailbox.isClosed // fence plus volatile read
    if (isClosed) None
    else childrenRefs.get(name)
  }

  final def tell(message: Any, sender: ActorRef): Unit =
    dispatcher.dispatch(this, Envelope(message, if (sender eq null) app.deadLetters else sender))

  final def sender: ActorRef = currentMessage match {
    case null                      ⇒ app.deadLetters
    case msg if msg.sender ne null ⇒ msg.sender
    case _                         ⇒ app.deadLetters
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

  final def systemInvoke(message: SystemMessage) {

    def create(): Unit = try {
      val created = newActor()
      actor = created
      created.preStart()
      checkReceiveTimeout
      if (app.AkkaConfig.DebugLifecycle) app.eventStream.publish(Debug(self, "started (" + actor + ")"))
    } catch {
      case e ⇒
        try {
          app.eventStream.publish(Error(e, self, "error while creating actor"))
          // prevent any further messages to be processed until the actor has been restarted
          dispatcher.suspend(this)
        } finally {
          parent.tell(Failed(ActorInitializationException(self, "exception during creation", e)), self)
        }
    }

    def recreate(cause: Throwable): Unit = try {
      val failedActor = actor
      if (app.AkkaConfig.DebugLifecycle) app.eventStream.publish(Debug(self, "restarting"))
      val freshActor = newActor()
      if (failedActor ne null) {
        val c = currentMessage //One read only plz
        try {
          failedActor.preRestart(cause, if (c ne null) Some(c.message) else None)
        } finally {
          clearActorContext()
          currentMessage = null
          actor = null
        }
      }
      actor = freshActor // assign it here so if preStart fails, we can null out the sef-refs next call
      freshActor.postRestart(cause)
      if (app.AkkaConfig.DebugLifecycle) app.eventStream.publish(Debug(self, "restarted"))

      dispatcher.resume(this) //FIXME should this be moved down?

      props.faultHandler.handleSupervisorRestarted(cause, self, children)
    } catch {
      case e ⇒ try {
        app.eventStream.publish(Error(e, self, "error while creating actor"))
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
        if (app.AkkaConfig.DebugLifecycle) app.eventStream.publish(Debug(self, "stopping"))
        for (child ← c) child.stop()
        stopping = true
      }
    }

    def supervise(child: ActorRef): Unit = {
      val stats = childrenStats
      if (!stats.contains(child)) {
        childrenRefs = childrenRefs.updated(child.name, child)
        childrenStats = childrenStats.updated(child, ChildRestartStats())
        if (app.AkkaConfig.DebugLifecycle) app.eventStream.publish(Debug(self, "now supervising " + child))
      } else app.eventStream.publish(Warning(self, "Already supervising " + child))
    }

    try {
      val isClosed = mailbox.isClosed //Fence plus volatile read
      if (!isClosed) {
        if (stopping) message match {
          case Terminate() ⇒ terminate() // to allow retry
          case _           ⇒
        }
        else message match {
          case Create()        ⇒ create()
          case Recreate(cause) ⇒ recreate(cause)
          case Link(subject) ⇒
            app.deathWatch.subscribe(self, subject)
            if (app.AkkaConfig.DebugLifecycle) app.eventStream.publish(Debug(self, "now monitoring " + subject))
          case Unlink(subject) ⇒
            app.deathWatch.unsubscribe(self, subject)
            if (app.AkkaConfig.DebugLifecycle) app.eventStream.publish(Debug(self, "stopped monitoring " + subject))
          case Suspend()        ⇒ suspend()
          case Resume()         ⇒ resume()
          case Terminate()      ⇒ terminate()
          case Supervise(child) ⇒ supervise(child)
        }
      }
    } catch {
      case e ⇒ //Should we really catch everything here?
        app.eventStream.publish(Error(e, self, "error while processing " + message))
        //TODO FIXME How should problems here be handled?
        throw e
    }
  }

  final def invoke(messageHandle: Envelope) {
    try {
      val isClosed = mailbox.isClosed //Fence plus volatile read
      if (!isClosed) {
        currentMessage = messageHandle
        try {
          try {
            cancelReceiveTimeout() // FIXME: leave this here?
            messageHandle.message match {
              case msg: AutoReceivedMessage ⇒ autoReceiveMessage(messageHandle)
              case msg ⇒
                if (stopping) {
                  // receiving Terminated in response to stopping children is too common to generate noise
                  if (!msg.isInstanceOf[Terminated]) app.deadLetterMailbox.enqueue(self, messageHandle)
                } else {
                  actor(msg)
                }
            }
            currentMessage = null // reset current message after successful invocation
          } catch {
            case e ⇒
              app.eventStream.publish(Error(e, self, e.getMessage))

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
            app.eventStream.publish(Error(e, self, e.getMessage))
            throw e
        }
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
    if (app.AkkaConfig.DebugAutoReceive) app.eventStream.publish(Debug(self, "received AutoReceiveMessage " + msg))

    if (stopping) msg.message match {
      case ChildTerminated ⇒ handleChildTerminated(sender)
      case _               ⇒ app.deadLetterMailbox.enqueue(self, msg)
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
    app.provider.evict(self.path.toString)
    dispatcher.detach(this)

    try {
      val a = actor
      if (a ne null) a.postStop()
    } finally {
      try {
        parent.tell(ChildTerminated, self)
        app.deathWatch.publish(Terminated(self))
        if (app.AkkaConfig.DebugLifecycle) app.eventStream.publish(Debug(self, "stopped"))
      } finally {
        currentMessage = null
        clearActorContext()
      }
    }
  }

  final def handleFailure(child: ActorRef, cause: Throwable): Unit = childrenStats.get(child) match {
    case Some(stats) ⇒ if (!props.faultHandler.handleFailure(child, cause, stats, childrenStats)) throw cause
    case None        ⇒ app.eventStream.publish(Warning(self, "dropping Failed(" + cause + ") from unknown child"))
  }

  final def handleChildTerminated(child: ActorRef): Unit = {
    childrenRefs -= child.name
    childrenStats -= child
    props.faultHandler.handleChildTerminated(child, children)
    if (stopping && childrenStats.isEmpty) doTerminate()
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def restart(cause: Throwable): Unit = dispatcher.systemDispatch(this, Recreate(cause))

  final def checkReceiveTimeout() {
    cancelReceiveTimeout()
    val recvtimeout = receiveTimeout
    if (recvtimeout.isDefined && dispatcher.mailboxIsEmpty(this)) {
      //Only reschedule if desired and there are currently no more messages to be processed
      futureTimeout = Some(app.scheduler.scheduleOnce(self, ReceiveTimeout, recvtimeout.get, TimeUnit.MILLISECONDS))
    }
  }

  final def cancelReceiveTimeout() {
    if (futureTimeout.isDefined) {
      futureTimeout.get.cancel()
      futureTimeout = None
    }
  }

  final def clearActorContext(): Unit = setActorContext(null)

  final def setActorContext(newContext: ActorContext) {
    @tailrec
    def lookupAndSetSelfFields(clazz: Class[_], actor: Actor, newContext: ActorContext): Boolean = {
      val success = try {
        val contextField = clazz.getDeclaredField("context")
        contextField.setAccessible(true)
        contextField.set(actor, newContext)
        true
      } catch {
        case e: NoSuchFieldException ⇒ false
      }

      if (success) true
      else {
        val parent = clazz.getSuperclass
        if (parent eq null)
          throw new IllegalActorStateException(toString + " is not an Actor since it have not mixed in the 'Actor' trait")
        lookupAndSetSelfFields(parent, actor, newContext)
      }
    }
    val a = actor
    if (a ne null)
      lookupAndSetSelfFields(a.getClass, a, newContext)
  }
}
