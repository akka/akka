/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import scala.annotation.tailrec
import akka.actor.{ PreRestartException, PostRestartException, InternalActorRef, Failed, ActorRef, ActorInterruptedException, ActorCell, Actor }
import akka.dispatch.{ Envelope, ChildTerminated }
import akka.event.Logging.{ Warning, Error, Debug }
import scala.util.control.NonFatal
import akka.dispatch.SystemMessage
import akka.event.Logging

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
  private var _failed: ActorRef = null
  private def isFailed: Boolean = _failed != null
  private def setFailed(perpetrator: ActorRef): Unit = _failed = perpetrator
  private def clearFailed(): Unit = _failed = null
  private def perpetrator: ActorRef = _failed

  /**
   * Do re-create the actor in response to a failure.
   */
  protected def faultRecreate(cause: Throwable): Unit =
    if (isNormal) {
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
  protected def faultResume(causedByFailure: Throwable): Unit = {
    if ((actor == null || actor.context == null) && causedByFailure != null) {
      system.eventStream.publish(Error(self.path.toString, clazz(actor),
        "changing Resume into Restart after " + causedByFailure))
      faultRecreate(causedByFailure)
    } else {
      val perp = perpetrator
      // done always to keep that suspend counter balanced
      // must happen “atomically”
      try resumeNonRecursive()
      finally if (causedByFailure != null) clearFailed()
      resumeChildren(causedByFailure, perp)
    }
  }

  protected def terminate() {
    setReceiveTimeout(None)
    cancelReceiveTimeout

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

  final def handleInvokeFailure(childrenNotToSuspend: Iterable[ActorRef], t: Throwable, message: String): Unit = {
    publish(Error(t, self.path.toString, clazz(actor), message))
    // prevent any further messages to be processed until the actor has been restarted
    if (!isFailed) try {
      suspendNonRecursive()
      // suspend children
      val skip: Set[ActorRef] = currentMessage match {
        case Envelope(Failed(_, _), child) ⇒ setFailed(child); Set(child)
        case _                             ⇒ setFailed(self); Set.empty
      }
      suspendChildren(exceptFor = skip ++ childrenNotToSuspend)
      // tell supervisor
      t match { // Wrap InterruptedExceptions and rethrow
        case _: InterruptedException ⇒ parent.tell(Failed(new ActorInterruptedException(t), uid), self); throw t
        case _                       ⇒ parent.tell(Failed(t, uid), self)
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
    try if (a ne null) a.postStop()
    finally try dispatcher.detach(this)
    finally try parent.sendSystemMessage(ChildTerminated(self))
    finally try tellWatchersWeDied(a)
    finally try unwatchWatchedActors(a)
    finally {
      if (system.settings.DebugLifecycle)
        publish(Debug(self.path.toString, clazz(a), "stopped"))
      clearActorFields(a)
      actor = null
    }
  }

  private def finishRecreate(cause: Throwable, failedActor: Actor): Unit = {
    // need to keep a snapshot of the surviving children before the new actor instance creates new ones
    val survivors = children

    try {
      try resumeNonRecursive()
      finally clearFailed() // must happen in any case, so that failure is propagated

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
        handleInvokeFailure(survivors, new PostRestartException(self, e, cause), e.getMessage)
    }
  }

  final protected def handleFailure(child: ActorRef, cause: Throwable, uid: Int): Unit =
    getChildByRef(child) match {
      /*
       * only act upon the failure, if it comes from a currently known child;
       * the UID protects against reception of a Failed from a child which was
       * killed in preRestart and re-created in postRestart
       */
      case Some(stats) if stats.uid == uid ⇒
        if (!actor.supervisorStrategy.handleFailure(this, child, cause, stats, getAllChildStats)) throw cause
      case Some(stats) ⇒
        publish(Debug(self.path.toString, clazz(actor),
          "dropping Failed(" + cause + ") from old child " + child + " (uid=" + stats.uid + " != " + uid + ")"))
      case None ⇒
        publish(Debug(self.path.toString, clazz(actor), "dropping Failed(" + cause + ") from unknown child " + child))
    }

  final protected def handleChildTerminated(child: ActorRef): SystemMessage = {
    val status = removeChildAndGetStateChange(child)
    /*
     * if this fails, we do nothing in case of terminating/restarting state, 
     * otherwise tell the supervisor etc. (in that second case, the match
     * below will hit the empty default case, too)
     */
    try actor.supervisorStrategy.handleChildTerminated(this, child, children)
    catch {
      case NonFatal(e) ⇒ handleInvokeFailure(Nil, e, "handleChildTerminated failed")
    }
    /*
     * if the removal changed the state of the (terminating) children container,
     * then we are continuing the previously suspended recreate/terminate action
     */
    status match {
      case Some(ChildrenContainer.Recreation(cause, todo)) ⇒ finishRecreate(cause, actor); SystemMessage.reverse(todo)
      case Some(ChildrenContainer.Termination) ⇒ finishTerminate(); null
      case _ ⇒ null
    }
  }
}
