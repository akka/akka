/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.event.EventHandler
import akka.config.Supervision._
import akka.dispatch._
import akka.util._
import java.util.{ Collection ⇒ JCollection }
import java.util.concurrent.{ ScheduledFuture, ConcurrentHashMap, TimeUnit }
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Stack

private[akka] object ActorInstance {
  sealed trait Status
  object Status {
    object Unstarted extends Status
    object Running extends Status
    object BeingRestarted extends Status
    object Shutdown extends Status
  }

  val refStack = new ThreadLocal[Stack[ScalaActorRef with SelfActorRef]] {
    override def initialValue = Stack[ScalaActorRef with SelfActorRef]()
  }
}

private[akka] class ActorInstance(props: Props, self: LocalActorRef) {
  import ActorInstance._

  val guard = new ReentrantGuard // TODO: remove this last synchronization point

  @volatile
  var status: Status = Status.Unstarted

  @volatile
  var mailbox: AnyRef = _

  @volatile
  var futureTimeout: Option[ScheduledFuture[AnyRef]] = None

  @volatile
  var _supervisor: Option[ActorRef] = None

  @volatile
  var maxNrOfRetriesCount: Int = 0

  @volatile
  var restartTimeWindowStartNanos: Long = 0L

  @volatile
  lazy val _linkedActors = new ConcurrentHashMap[Uuid, ActorRef]

  val actor: AtomicReference[Actor] = new AtomicReference[Actor]()

  def ref: ActorRef = self

  def uuid: Uuid = self.uuid

  def actorClass: Class[_] = actor.get.getClass

  def dispatcher: MessageDispatcher = props.dispatcher

  def isRunning: Boolean = status match {
    case Status.BeingRestarted | Status.Running ⇒ true
    case _                                      ⇒ false
  }

  def isShutdown: Boolean = status == Status.Shutdown

  def start(): Unit = guard.withGuard {
    if (isShutdown) throw new ActorStartException("Can't start an actor that has been stopped")
    if (!isRunning) {
      if (props.supervisor.isDefined) props.supervisor.get.link(self)
      actor.set(newActor)
      dispatcher.attach(this)
      status = Status.Running
      try {
        val a = actor.get
        if (Actor.debugLifecycle) EventHandler.debug(a, "started")
        a.preStart()
        Actor.registry.register(self)
        checkReceiveTimeout // schedule the initial receive timeout
      } catch {
        case e ⇒
          status = Status.Unstarted
          throw e
      }
    }
  }

  def newActor: Actor = {
    val stackBefore = refStack.get
    refStack.set(stackBefore.push(self))
    try {
      if (status == Status.BeingRestarted) {
        val a = actor.get()
        val fresh = try a.freshInstance catch {
          case e ⇒
            EventHandler.error(e, a, "freshInstance() failed, falling back to initial actor factory")
            None
        }
        fresh match {
          case Some(actor) ⇒ actor
          case None        ⇒ props.creator()
        }
      } else {
        props.creator()
      }
    } finally {
      val stackAfter = refStack.get
      if (stackAfter.nonEmpty)
        refStack.set(if (stackAfter.head eq null) stackAfter.pop.pop else stackAfter.pop) //pop null marker plus self
    }
  } match {
    case null  ⇒ throw new ActorInitializationException("Actor instance passed to actorOf can't be 'null'")
    case valid ⇒ valid
  }

  def suspend(): Unit = dispatcher.suspend(this)

  def resume(): Unit = dispatcher.resume(this)

  def stop(): Unit = guard.withGuard {
    if (isRunning) {
      self.receiveTimeout = None
      cancelReceiveTimeout
      Actor.registry.unregister(self)
      status = Status.Shutdown
      dispatcher.detach(this)
      try {
        val a = actor.get
        if (Actor.debugLifecycle) EventHandler.debug(a, "stopping")
        a.postStop()
        stopSupervisedActors()
      } finally {
        self.currentMessage = null
        setActorSelf(null)
      }
    }
  }

  def stopSupervisedActors(): Unit = guard.withGuard {
    val i = _linkedActors.values.iterator
    while (i.hasNext) {
      i.next.stop()
      i.remove()
    }
  }

  def link(actorRef: ActorRef): ActorRef = {
    guard.withGuard {
      val actorRefSupervisor = actorRef.supervisor
      val hasSupervisorAlready = actorRefSupervisor.isDefined
      if (hasSupervisorAlready && actorRefSupervisor.get.uuid == self.uuid) return actorRef // we already supervise this guy
      else if (hasSupervisorAlready) throw new IllegalActorStateException(
        "Actor can only have one supervisor [" + actorRef + "], e.g. link(actor) fails")
      else {
        _linkedActors.put(actorRef.uuid, actorRef)
        actorRef.supervisor = Some(self)
      }
    }
    if (Actor.debugLifecycle) EventHandler.debug(actor.get(), "now supervising " + actorRef)
    actorRef
  }

  def unlink(actorRef: ActorRef): ActorRef = {
    guard.withGuard {
      if (_linkedActors.remove(actorRef.uuid) eq null)
        throw new IllegalActorStateException("Actor [" + actorRef + "] is not a linked actor, can't unlink")
      actorRef.supervisor = None
      if (Actor.debugLifecycle) EventHandler.debug(actor.get(), "stopped supervising " + actorRef)
    }
    actorRef
  }

  def linkedActors: JCollection[ActorRef] = java.util.Collections.unmodifiableCollection(_linkedActors.values)

  def supervisor: Option[ActorRef] = _supervisor

  def supervisor_=(sup: Option[ActorRef]): Unit = _supervisor = sup

  def sender: Option[ActorRef] = {
    val msg = self.currentMessage
    if (msg eq null) None
    else msg.channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
  }

  def senderFuture(): Option[Promise[Any]] = {
    val msg = self.currentMessage
    if (msg eq null) None
    else msg.channel match {
      case f: ActorPromise ⇒ Some(f)
      case _               ⇒ None
    }
  }

  def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit =
    if (isRunning) dispatcher dispatchMessage new MessageInvocation(this, message, channel)
    else throw new ActorInitializationException("Actor has not been started")

  def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = if (isRunning) {
    val future = channel match {
      case f: ActorPromise ⇒ f
      case _               ⇒ new ActorPromise(timeout)(dispatcher)
    }
    dispatcher dispatchMessage new MessageInvocation(this, message, future)
    future
  } else throw new ActorInitializationException("Actor has not been started")

  def invoke(messageHandle: MessageInvocation): Unit = {
    guard.lock.lock()
    try {
      if (!isShutdown) {
        self.currentMessage = messageHandle
        try {
          try {
            cancelReceiveTimeout() // FIXME: leave this here?
            actor.get().apply(messageHandle.message)
            self.currentMessage = null // reset current message after successful invocation
          } catch {
            case e ⇒
              EventHandler.error(e, self, e.getMessage)

              // prevent any further messages to be processed until the actor has been restarted
              dispatcher.suspend(this)

              self.channel.sendException(e)

              if (supervisor.isDefined) supervisor.get ! Death(self, e, true) else dispatcher.resume(this)

              if (e.isInstanceOf[InterruptedException]) throw e //Re-throw InterruptedExceptions as expected
          } finally {
            checkReceiveTimeout // Reschedule receive timeout
          }
        } catch {
          case e ⇒
            EventHandler.error(e, actor.get(), e.getMessage)
            throw e
        }
      } else {
        // throwing away message if actor is shut down, no use throwing an exception in receiving actor's thread, isShutdown is enforced on caller side
      }
    } finally {
      guard.lock.unlock()
    }
  }

  def handleDeath(death: Death) {
    props.faultHandler match {
      case AllForOnePermanentStrategy(trapExit, maxRetries, within) if trapExit.exists(_.isAssignableFrom(death.cause.getClass)) ⇒
        restartLinkedActors(death.cause, maxRetries, within)

      case AllForOneTemporaryStrategy(trapExit) if trapExit.exists(_.isAssignableFrom(death.cause.getClass)) ⇒
        restartLinkedActors(death.cause, None, None)

      case OneForOnePermanentStrategy(trapExit, maxRetries, within) if trapExit.exists(_.isAssignableFrom(death.cause.getClass)) ⇒
        death.deceased.restart(death.cause, maxRetries, within)

      case OneForOneTemporaryStrategy(trapExit) if trapExit.exists(_.isAssignableFrom(death.cause.getClass)) ⇒
        unlink(death.deceased)
        death.deceased.stop()
        self ! MaximumNumberOfRestartsWithinTimeRangeReached(death.deceased, None, None, death.cause)

      case _ ⇒
        if (_supervisor.isDefined) throw death.cause else death.deceased.stop() //Escalate problem if not handled here
    }
  }

  def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    def performRestart() {
      val failedActor = actor.get
      if (Actor.debugLifecycle) EventHandler.debug(failedActor, "restarting")
      val message = if (self.currentMessage ne null) Some(self.currentMessage.message) else None
      failedActor.preRestart(reason, message)
      val freshActor = newActor
      setActorSelf(null) // only null out the references if we could instantiate the new actor
      actor.set(freshActor) // assign it here so if preStart fails, we can null out the sef-refs next call
      freshActor.postRestart(reason)
      if (Actor.debugLifecycle) EventHandler.debug(freshActor, "restarted")
    }

    @tailrec
    def attemptRestart() {
      val success = if (requestRestartPermission(maxNrOfRetries, withinTimeRange)) {
        guard.withGuard[Boolean] {
          status = Status.BeingRestarted

          val success =
            try {
              performRestart()
              true
            } catch {
              case e ⇒
                EventHandler.error(e, self, "Exception in restart of Actor [%s]".format(toString))
                false // an error or exception here should trigger a retry
            } finally {
              self.currentMessage = null
            }

          if (success) {
            status = Status.Running
            dispatcher.resume(this)
            restartLinkedActors(reason, maxNrOfRetries, withinTimeRange)
          }
          success
        }
      } else {
        // tooManyRestarts
        if (supervisor.isDefined)
          supervisor.get ! MaximumNumberOfRestartsWithinTimeRangeReached(self, maxNrOfRetries, withinTimeRange, reason)
        stop()
        true // done
      }

      if (success) () // alles gut
      else attemptRestart()
    }

    attemptRestart() // recur
  }

  def requestRestartPermission(maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Boolean = {
    val denied = if (maxNrOfRetries.isEmpty && withinTimeRange.isEmpty) {
      // immortal
      false
    } else if (withinTimeRange.isEmpty) {
      // restrict number of restarts
      val retries = maxNrOfRetriesCount + 1
      maxNrOfRetriesCount = retries //Increment number of retries
      retries > maxNrOfRetries.get
    } else {
      // cannot restart more than N within M timerange
      val retries = maxNrOfRetriesCount + 1

      val windowStart = restartTimeWindowStartNanos
      val now = System.nanoTime
      // we are within the time window if it isn't the first restart, or if the window hasn't closed
      val insideWindow = if (windowStart == 0) true else (now - windowStart) <= TimeUnit.MILLISECONDS.toNanos(withinTimeRange.get)

      if (windowStart == 0 || !insideWindow) //(Re-)set the start of the window
        restartTimeWindowStartNanos = now

      // reset number of restarts if window has expired, otherwise, increment it
      maxNrOfRetriesCount = if (windowStart != 0 && !insideWindow) 1 else retries // increment number of retries

      val restartCountLimit = if (maxNrOfRetries.isDefined) maxNrOfRetries.get else 1

      // the actor is dead if it dies X times within the window of restart
      insideWindow && retries > restartCountLimit
    }

    denied == false // if we weren't denied, we have a go
  }

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = {
    props.faultHandler.lifeCycle match {
      case Temporary ⇒
        val i = _linkedActors.values.iterator
        while (i.hasNext) {
          val actorRef = i.next()

          i.remove()

          actorRef.stop()
          // when this comes down through the handleDeath path, we get here when the temp actor is restarted
          if (supervisor.isDefined) {
            supervisor.get ! MaximumNumberOfRestartsWithinTimeRangeReached(actorRef, Some(0), None, reason)

            //FIXME if last temporary actor is gone, then unlink me from supervisor <-- should this exist?
            if (!i.hasNext)
              supervisor.get ! UnlinkAndStop(self)
          }
        }

      case Permanent ⇒
        val i = _linkedActors.values.iterator
        while (i.hasNext) i.next().restart(reason, maxNrOfRetries, withinTimeRange)
    }
  }

  def checkReceiveTimeout() {
    cancelReceiveTimeout()
    val recvtimeout = self.receiveTimeout
    if (recvtimeout.isDefined && dispatcher.mailboxIsEmpty(this)) {
      //Only reschedule if desired and there are currently no more messages to be processed
      futureTimeout = Some(Scheduler.scheduleOnce(self, ReceiveTimeout, recvtimeout.get, TimeUnit.MILLISECONDS))
    }
  }

  def cancelReceiveTimeout() {
    if (futureTimeout.isDefined) {
      futureTimeout.get.cancel(true)
      futureTimeout = None
    }
  }

  def setActorSelf(value: ActorRef): Unit = {
    @tailrec
    def lookupAndSetSelfFields(clazz: Class[_], actor: Actor, value: ActorRef): Boolean = {
      val success = try {
        val selfField = clazz.getDeclaredField("self")
        val someSelfField = clazz.getDeclaredField("someSelf")
        selfField.setAccessible(true)
        someSelfField.setAccessible(true)
        selfField.set(actor, value)
        someSelfField.set(actor, if (value ne null) Some(value) else null)
        true
      } catch {
        case e: NoSuchFieldException ⇒ false
      }

      if (success) true
      else {
        val parent = clazz.getSuperclass
        if (parent eq null)
          throw new IllegalActorStateException(toString + " is not an Actor since it have not mixed in the 'Actor' trait")
        lookupAndSetSelfFields(parent, actor, value)
      }
    }

    lookupAndSetSelfFields(actor.get.getClass, actor.get, value)
  }
}
