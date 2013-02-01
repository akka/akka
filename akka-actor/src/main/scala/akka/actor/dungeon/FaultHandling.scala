/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.dungeon

import scala.annotation.tailrec
import akka.actor.{ PreRestartException, PostRestartException, InternalActorRef, ActorRef, ActorInterruptedException, ActorCell, Actor }
import akka.dispatch._
import akka.event.Logging.{ Warning, Error, Debug }
import scala.util.control.NonFatal
import akka.event.Logging
import scala.collection.immutable
import akka.dispatch.ChildTerminated
import akka.actor.PreRestartException
import akka.actor.PostRestartException
import akka.event.Logging.Debug
import scala.concurrent.duration.Duration

private[akka] class FailedContainer(val perp: ActorRef, var queue: SystemMessage)

private[akka] trait FaultHandling { this: ActorCell ⇒

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

  /*
   * have we told our supervisor that we Failed() and have not yet heard back?
   * (actually: we might have heard back but not yet acted upon it, in case of
   * a restart with dying children)
   * might well be replaced by ref to a Cancellable in the future (see #2299)
   */
  private var _failed: FailedContainer = null
  private def isFailed: Boolean = _failed != null
  private def setFailed(perpetrator: ActorRef): Unit = _failed = new FailedContainer(perpetrator, null)
  private def queueFailed(msg: SystemMessage): Unit = { msg.next = _failed.queue; _failed.queue = msg }
  private def clearFailed(): SystemMessage =
    if (_failed != null) {
      val ret = _failed.queue
      _failed = null
      SystemMessage.reverse(ret)
    } else null
  private def perpetrator: ActorRef = if (_failed == null) null else _failed.perp

  /**
   * Do re-create the actor in response to a failure.
   */
  protected def faultRecreate(cause: Throwable): SystemMessage =
    if (actor == null) {
      system.eventStream.publish(Error(self.path.toString, clazz(actor),
        "changing Recreate into Create after " + cause))
      faultCreate()
    } else if (isNormal) {
      val failedActor = actor
      if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(failedActor), "restarting"))
      if (failedActor ne null) {
        val optionalMessage = if (currentMessage ne null) Some(currentMessage.message) else None
        try {
          // if the actor fails in preRestart, we can do nothing but log it: it’s best-effort
          if (failedActor.context ne null) failedActor.preRestart(cause, optionalMessage)
        } catch {
          case NonFatal(e) ⇒
            val ex = new PreRestartException(self, e, cause, optionalMessage)
            publish(Error(ex, self.path.toString, clazz(failedActor), e.getMessage))
        } finally {
          clearActorFields(failedActor)
        }
      }
      assert(mailbox.isSuspended, "mailbox must be suspended during restart, status=" + mailbox.status)
      if (!setChildrenTerminationReason(ChildrenContainer.Recreation(cause))) finishRecreate(cause, failedActor)
      else null
    } else {
      // need to keep that suspend counter balanced
      faultResume(causedByFailure = null)
    }

  /**
   * Do suspend the actor in response to a failure of a parent (i.e. the
   * “recursive suspend” feature).
   */
  protected def faultSuspend(): Unit = {
    // done always to keep that suspend counter balanced
    suspendNonRecursive()
    suspendChildren()
  }

  /**
   * Do resume the actor in response to a failure.
   *
   * @param causedByFailure signifies if it was our own failure which
   *        prompted this action.
   */
  protected def faultResume(causedByFailure: Throwable): SystemMessage = {
    if (actor == null) {
      system.eventStream.publish(Error(self.path.toString, clazz(actor),
        "changing Resume into Create after " + causedByFailure))
      faultCreate()
    } else if (actor.context == null && causedByFailure != null) {
      system.eventStream.publish(Error(self.path.toString, clazz(actor),
        "changing Resume into Restart after " + causedByFailure))
      faultRecreate(causedByFailure)
    } else {
      val perp = perpetrator
      var queue: SystemMessage = null
      // done always to keep that suspend counter balanced
      // must happen “atomically”
      try resumeNonRecursive()
      finally if (causedByFailure != null) queue = clearFailed()
      resumeChildren(causedByFailure, perp)
      queue
    }
  }

  /**
   * Do create the actor in response to a failure.
   */
  protected def faultCreate(): SystemMessage = {
    assert(mailbox.isSuspended, "mailbox must be suspended during failed creation, status=" + mailbox.status)
    assert(perpetrator == self)

    setReceiveTimeout(Duration.Undefined)
    cancelReceiveTimeout

    // stop all children, which will turn childrenRefs into TerminatingChildrenContainer (if there are children)
    children foreach stop

    if (!setChildrenTerminationReason(ChildrenContainer.Creation())) finishCreate()
    else null
  }

  private def finishCreate(): SystemMessage = {
    var queue: SystemMessage = null

    try resumeNonRecursive()
    finally queue = clearFailed()

    try {
      create(uid)
      queue
    } catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        handleInvokeFailure(Nil, e, s"error while processing Create($uid)")
        if (queue != null) queueFailed(SystemMessage.reverse(queue))
        null
    }
  }

  protected def terminate() {
    setReceiveTimeout(Duration.Undefined)
    cancelReceiveTimeout

    // prevent Deadletter(Terminated) messages
    unwatchWatchedActors(actor)

    // stop all children, which will turn childrenRefs into TerminatingChildrenContainer (if there are children)
    children foreach stop

    val wasTerminating = isTerminating

    if (setChildrenTerminationReason(ChildrenContainer.Termination)) {
      if (!wasTerminating) {
        // do not process normal messages while waiting for all children to terminate
        suspendNonRecursive()
        // do not propagate failures during shutdown to the supervisor
        setFailed(self)
        if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "stopping"))
      }
    } else {
      setTerminated()
      finishTerminate()
    }
  }

  final def handleInvokeFailure(childrenNotToSuspend: immutable.Iterable[ActorRef], t: Throwable, message: String): Unit = {
    publish(Error(t, self.path.toString, clazz(actor), message))
    // prevent any further messages to be processed until the actor has been restarted
    if (!isFailed) try {
      suspendNonRecursive()
      // suspend children
      val skip: Set[ActorRef] = currentMessage match {
        case Envelope(Failed(_, _, _), child) ⇒ { setFailed(child); Set(child) }
        case _                                ⇒ { setFailed(self); Set.empty }
      }
      suspendChildren(exceptFor = skip ++ childrenNotToSuspend)
      // tell supervisor
      t match { // Wrap InterruptedExceptions and, clear the flag and rethrow
        case _: InterruptedException ⇒
          // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          parent.sendSystemMessage(Failed(self, new ActorInterruptedException(t), uid))
          Thread.interrupted() // clear interrupted flag before throwing according to java convention
          throw t
        // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
        case _ ⇒ parent.sendSystemMessage(Failed(self, t, uid))
      }
    } catch {
      case NonFatal(e) ⇒
        publish(Error(e, self.path.toString, clazz(actor),
          "emergency stop: exception in failure handling for " + t.getClass + Logging.stackTraceFor(t)))
        try children foreach stop
        finally finishTerminate()
    }
  }

  private def finishTerminate() {
    val a = actor
    /* The following order is crucial for things to work properly. Only change this if you're very confident and lucky.
     *
     * Please note that if a parent is also a watcher then ChildTerminated and Terminated must be processed in this
     * specific order.
     */
    try if (a ne null) a.postStop()
    catch {
      case NonFatal(e) ⇒ publish(Error(e, self.path.toString, clazz(a), e.getMessage))
    } finally try dispatcher.detach(this)
    finally try parent.sendSystemMessage(ChildTerminated(self))
    finally try parent ! NullMessage // read ScalaDoc of NullMessage to see why
    finally try tellWatchersWeDied(a)
    finally try unwatchWatchedActors(a) // stay here as we expect an emergency stop from handleInvokeFailure
    finally {
      if (system.settings.DebugLifecycle)
        publish(Debug(self.path.toString, clazz(a), "stopped"))
      clearActorFields(a)
      actor = null
    }
  }

  private def finishRecreate(cause: Throwable, failedActor: Actor): SystemMessage = {
    // need to keep a snapshot of the surviving children before the new actor instance creates new ones
    val survivors = children

    /*
     * We clear the “failed” status first, then try to recreate; if that fails, then we 
     * are immediately failed again, thus we need to re-enqueue whatever system messages
     * were dequeued when clearing the status. Otherwise return the dequeued messages.
     */
    var queue: SystemMessage = null
    try {
      try resumeNonRecursive()
      finally queue = clearFailed() // must happen in any case, so that failure is propagated

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
      queue
    } catch {
      case e @ (NonFatal(_) | _: InterruptedException) ⇒
        clearActorFields(actor) // in order to prevent preRestart() from happening again
        handleInvokeFailure(survivors, new PostRestartException(self, e, cause), e.getMessage)
        if (queue != null) queueFailed(SystemMessage.reverse(queue))
        null
    }
  }

  final protected def handleFailure(f: Failed): Unit = {
    currentMessage = Envelope(f, f.child, system)
    getChildByRef(f.child) match {
      /*
       * only act upon the failure, if it comes from a currently known child;
       * the UID protects against reception of a Failed from a child which was
       * killed in preRestart and re-created in postRestart
       */
      case Some(stats) if stats.uid == uid ⇒
        /*
         * If we receive a Failed message while being Failed ourselves, we need to
         * enqueue that message for later, when we clear the failed status.
         */
        if (isFailed) queueFailed(f)
        else if (!actor.supervisorStrategy.handleFailure(this, f.child, f.cause, stats, getAllChildStats)) throw f.cause
      case Some(stats) ⇒
        publish(Debug(self.path.toString, clazz(actor),
          s"dropping Failed(${f.cause}) from old child ${f.child} (uid=${stats.uid} != $uid)"))
      case None ⇒
        publish(Debug(self.path.toString, clazz(actor), "dropping Failed(" + f.cause + ") from unknown child " + f.child))
    }
  }

  final protected def handleChildTerminated(child: ActorRef): SystemMessage = {
    val status = removeChildAndGetStateChange(child)
    /*
     * if this fails, we do nothing in case of terminating/restarting state, 
     * otherwise tell the supervisor etc. (in that second case, the match
     * below will hit the empty default case, too)
     */
    if (actor != null) {
      try actor.supervisorStrategy.handleChildTerminated(this, child, children)
      catch {
        case NonFatal(e) ⇒ handleInvokeFailure(Nil, e, "handleChildTerminated failed")
      }
    }
    /*
     * if the removal changed the state of the (terminating) children container,
     * then we are continuing the previously suspended recreate/create/terminate action
     */
    status match {
      case Some(c @ ChildrenContainer.Recreation(cause)) ⇒
        SystemMessage.concat(finishRecreate(cause, actor), c.dequeueAll())
      case Some(c @ ChildrenContainer.Creation()) ⇒
        SystemMessage.concat(finishCreate(), c.dequeueAll())
      case Some(ChildrenContainer.Termination) ⇒
        finishTerminate()
        null
      case _ ⇒ null
    }
  }
}
