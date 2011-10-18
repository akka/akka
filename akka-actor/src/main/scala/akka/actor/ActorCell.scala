/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.util._
import scala.annotation.tailrec
import scala.collection.immutable.Stack
import scala.collection.JavaConverters
import java.util.concurrent.{ ScheduledFuture, TimeUnit }
import java.util.{ Collection ⇒ JCollection, Collections ⇒ JCollections }
import akka.AkkaApplication

/**
 * The actor context - the view of the actor cell from the actor.
 * Exposes contextual information for the actor and the current message.
 * TODO: everything here for current compatibility - could be limited more
 */
private[akka] trait ActorContext extends ActorRefFactory {

  def self: ActorRef with ScalaActorRef

  def receiveTimeout: Option[Long]

  def receiveTimeout_=(timeout: Option[Long]): Unit

  def hotswap: Stack[PartialFunction[Any, Unit]]

  def hotswap_=(stack: Stack[PartialFunction[Any, Unit]]): Unit

  def currentMessage: Envelope

  def currentMessage_=(invocation: Envelope): Unit

  def sender: Option[ActorRef]

  def senderFuture(): Option[Promise[Any]]

  def channel: UntypedChannel

  def children: Iterable[ActorRef]

  def dispatcher: MessageDispatcher

  def handleFailure(fail: Failed): Unit

  def handleChildTerminated(child: ActorRef): Unit

  def app: AkkaApplication

}

private[akka] object ActorCell {
  val contextStack = new ThreadLocal[Stack[ActorContext]] {
    override def initialValue = Stack[ActorContext]()
  }
}

//vars don't need volatile since it's protected with the mailbox status
//Make sure that they are not read/written outside of a message processing (systemInvoke/invoke)
private[akka] class ActorCell(
  val app: AkkaApplication,
  val self: ActorRef with ScalaActorRef,
  val props: Props,
  val supervisor: ActorRef,
  var receiveTimeout: Option[Long],
  var hotswap: Stack[PartialFunction[Any, Unit]]) extends ActorContext {

  import ActorCell._

  protected def guardian = self

  def provider = app.provider

  var futureTimeout: Option[ScheduledFuture[AnyRef]] = None //FIXME TODO Doesn't need to be volatile either, since it will only ever be accessed when a message is processed

  var _children: Vector[ChildRestartStats] = Vector.empty

  var currentMessage: Envelope = null

  var actor: Actor = _ //FIXME We can most probably make this just a regular reference to Actor

  def uuid: Uuid = self.uuid

  @inline
  final def dispatcher: MessageDispatcher = if (props.dispatcher == Props.defaultDispatcher) app.dispatcher else props.dispatcher

  def isShutdown: Boolean = mailbox.isClosed

  @volatile //This must be volatile since it isn't protected by the mailbox status
  var mailbox: Mailbox = _

  def start(): Unit = {
    mailbox = dispatcher.createMailbox(this)

    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    supervisor.sendSystemMessage(akka.dispatch.Supervise(self))

    dispatcher.attach(this)
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  def suspend(): Unit = dispatcher.systemDispatch(this, Suspend())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  def resume(): Unit = dispatcher.systemDispatch(this, Resume())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  private[akka] def stop(): Unit = dispatcher.systemDispatch(this, Terminate())

  def startsMonitoring(subject: ActorRef): ActorRef = {
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    dispatcher.systemDispatch(this, Link(subject))
    subject
  }

  def stopsMonitoring(subject: ActorRef): ActorRef = {
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    dispatcher.systemDispatch(this, Unlink(subject))
    subject
  }

  def children: Iterable[ActorRef] = _children.map(_.child)

  def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = dispatcher dispatch Envelope(this, message, channel)

  def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = {
    val future = channel match {
      case f: ActorPromise ⇒ f
      case _               ⇒ new ActorPromise(timeout)(dispatcher)
    }
    dispatcher dispatch Envelope(this, message, future)
    future
  }

  def sender: Option[ActorRef] = currentMessage match {
    case null                                      ⇒ None
    case msg if msg.channel.isInstanceOf[ActorRef] ⇒ Some(msg.channel.asInstanceOf[ActorRef])
    case _                                         ⇒ None
  }

  def senderFuture(): Option[Promise[Any]] = currentMessage match {
    case null ⇒ None
    case msg if msg.channel.isInstanceOf[ActorPromise] ⇒ Some(msg.channel.asInstanceOf[ActorPromise])
    case _ ⇒ None
  }

  def channel: UntypedChannel = currentMessage match {
    case null ⇒ NullChannel
    case msg  ⇒ msg.channel
  }

  //This method is in charge of setting up the contextStack and create a new instance of the Actor
  protected def newActor(): Actor = {
    val stackBefore = contextStack.get
    contextStack.set(stackBefore.push(this))
    try {
      val instance = props.creator()

      if (instance eq null)
        throw new ActorInitializationException("Actor instance passed to actorOf can't be 'null'")

      instance
    } finally {
      val stackAfter = contextStack.get
      if (stackAfter.nonEmpty)
        contextStack.set(if (stackAfter.head eq null) stackAfter.pop.pop else stackAfter.pop) // pop null marker plus our context
    }
  }

  def systemInvoke(message: SystemMessage) {

    def create(): Unit = try {
      val created = newActor()
      actor = created
      created.preStart()
      checkReceiveTimeout
      if (app.AkkaConfig.DebugLifecycle) app.eventHandler.debug(self, "started")
    } catch {
      case e ⇒ try {
        app.eventHandler.error(e, self, "error while creating actor")
        // prevent any further messages to be processed until the actor has been restarted
        dispatcher.suspend(this)
      } finally {
        supervisor ! Failed(self, e)
      }
    }

    def recreate(cause: Throwable): Unit = try {
      val failedActor = actor
      if (app.AkkaConfig.DebugLifecycle) app.eventHandler.debug(self, "restarting")
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
      if (app.AkkaConfig.DebugLifecycle) app.eventHandler.debug(self, "restarted")

      dispatcher.resume(this) //FIXME should this be moved down?

      props.faultHandler.handleSupervisorRestarted(cause, self, _children)
    } catch {
      case e ⇒ try {
        app.eventHandler.error(e, self, "error while creating actor")
        // prevent any further messages to be processed until the actor has been restarted
        dispatcher.suspend(this)
      } finally {
        supervisor ! Failed(self, e)
      }
    }

    def suspend(): Unit = dispatcher suspend this

    def resume(): Unit = dispatcher resume this

    def terminate() {
      receiveTimeout = None
      cancelReceiveTimeout
      app.provider.evict(self.address)
      dispatcher.detach(this)

      try {
        try {
          val a = actor
          if (app.AkkaConfig.DebugLifecycle) app.eventHandler.debug(self, "stopping")
          if (a ne null) a.postStop()
        } finally {
          //Stop supervised actors
          val links = _children
          if (links.nonEmpty) {
            _children = Vector.empty
            links.foreach(_.child.stop())
          }
        }
      } finally {
        try {
          val cause = new ActorKilledException("Stopped") //FIXME TODO make this an object, can be reused everywhere
          supervisor ! ChildTerminated(self, cause)
          app.deathWatch.publish(Terminated(self, cause))
        } finally {
          currentMessage = null
          clearActorContext()
        }
      }
    }

    def supervise(child: ActorRef): Unit = {
      val links = _children
      if (!links.exists(_.child == child)) {
        _children = links :+ ChildRestartStats(child)
        if (app.AkkaConfig.DebugLifecycle) app.eventHandler.debug(self, "now supervising " + child)
      } else app.eventHandler.warning(self, "Already supervising " + child)
    }

    try {
      val isClosed = mailbox.isClosed //Fence plus volatile read
      if (!isClosed) {
        message match {
          case Create()        ⇒ create()
          case Recreate(cause) ⇒ recreate(cause)
          case Link(subject) ⇒
            app.deathWatch.subscribe(self, subject)
            if (app.AkkaConfig.DebugLifecycle) app.eventHandler.debug(self, "now monitoring " + subject)
          case Unlink(subject) ⇒
            app.deathWatch.unsubscribe(self, subject)
            if (app.AkkaConfig.DebugLifecycle) app.eventHandler.debug(self, "stopped monitoring " + subject)
          case Suspend()        ⇒ suspend()
          case Resume()         ⇒ resume()
          case Terminate()      ⇒ terminate()
          case Supervise(child) ⇒ supervise(child)
        }
      }
    } catch {
      case e ⇒ //Should we really catch everything here?
        app.eventHandler.error(e, self, "error while processing " + message)
        //TODO FIXME How should problems here be handled?
        throw e
    }
  }

  def invoke(messageHandle: Envelope) {
    try {
      val isClosed = mailbox.isClosed //Fence plus volatile read
      if (!isClosed) {
        currentMessage = messageHandle
        try {
          try {
            cancelReceiveTimeout() // FIXME: leave this here?

            actor(messageHandle.message)
            currentMessage = null // reset current message after successful invocation
          } catch {
            case e ⇒
              app.eventHandler.error(e, self, e.getMessage)

              // prevent any further messages to be processed until the actor has been restarted
              dispatcher.suspend(this)

              channel.sendException(e)

              props.faultHandler.handleSupervisorFailing(self, _children)
              supervisor ! Failed(self, e)

              if (e.isInstanceOf[InterruptedException]) throw e //Re-throw InterruptedExceptions as expected
          } finally {
            checkReceiveTimeout // Reschedule receive timeout
          }
        } catch {
          case e ⇒
            app.eventHandler.error(e, self, e.getMessage)
            throw e
        }
      } else {
        messageHandle.channel sendException new ActorKilledException("Actor has been stopped")
        // throwing away message if actor is shut down, no use throwing an exception in receiving actor's thread, isShutdown is enforced on caller side
      }
    }
  }

  def handleFailure(fail: Failed): Unit = if (!props.faultHandler.handleFailure(fail, _children)) {
    throw fail.cause
  }

  def handleChildTerminated(child: ActorRef): Unit = _children = props.faultHandler.handleChildTerminated(child, _children)

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  def restart(cause: Throwable): Unit = dispatcher.systemDispatch(this, Recreate(cause))

  def checkReceiveTimeout() {
    cancelReceiveTimeout()
    val recvtimeout = receiveTimeout
    if (recvtimeout.isDefined && dispatcher.mailboxIsEmpty(this)) {
      //Only reschedule if desired and there are currently no more messages to be processed
      futureTimeout = Some(app.scheduler.scheduleOnce(self, ReceiveTimeout, recvtimeout.get, TimeUnit.MILLISECONDS))
    }
  }

  def cancelReceiveTimeout() {
    if (futureTimeout.isDefined) {
      futureTimeout.get.cancel(true)
      futureTimeout = None
    }
  }

  def clearActorContext(): Unit = setActorContext(null)

  def setActorContext(newContext: ActorContext) {
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

  override def hashCode: Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[ActorCell] && that.asInstanceOf[ActorCell].uuid == uuid
  }

  override def toString = "ActorCell[%s]".format(uuid)
}
