package akka.actor.cell

import akka.actor.PreRestartException
import akka.actor.ActorCell
import akka.util.NonFatal
import akka.actor.ActorRef
import akka.actor.PostRestartException
import akka.actor.Actor
import akka.dispatch.Envelope
import akka.dispatch.ChildTerminated
import akka.actor.Failed
import akka.actor.InternalActorRef
import akka.event.Logging._
import akka.actor.ActorInterruptedException

trait FaultHandling { this: ActorCell ⇒

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
  private var _failed = false
  private def currentlyFailed: Boolean = _failed
  private def setFailed(): Unit = _failed = true
  private def setNotFailed(): Unit = _failed = false

  protected def faultRecreate(cause: Throwable): Unit =
    if (isNormal) {
      val failedActor = actor
      if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(failedActor), "restarting"))
      if (failedActor ne null) {
        try {
          // if the actor fails in preRestart, we can do nothing but log it: it’s best-effort
          if (failedActor.context ne null) failedActor.preRestart(cause, Option(currentMessage))
        } catch {
          case NonFatal(e) ⇒
            val ex = new PreRestartException(self, e, cause, Option(currentMessage))
            publish(Error(ex, self.path.toString, clazz(failedActor), e.getMessage))
        } finally {
          clearActorFields(failedActor)
        }
      }
      assert(mailbox.isSuspended, "mailbox must be suspended during restart, status=" + mailbox.status)
      if (!setChildrenTerminationReason(ChildrenContainer.Recreation(cause))) finishRecreate(cause, failedActor)
    } else {
      // need to keep that suspend counter balanced
      faultResume(inResponseToFailure = false)
    }

  protected def faultSuspend(): Unit = {
    // done always to keep that suspend counter balanced
    suspendNonRecursive()
    suspendChildren()
  }

  protected def faultResume(inResponseToFailure: Boolean): Unit = {
    // done always to keep that suspend counter balanced
    // must happen “atomically”
    try resumeNonRecursive()
    finally if (inResponseToFailure) setNotFailed()
    resumeChildren()
  }

  protected def terminate() {
    setReceiveTimeout(None)
    cancelReceiveTimeout

    // stop all children, which will turn childrenRefs into TerminatingChildrenContainer (if there are children)
    children foreach stop

    if (setChildrenTerminationReason(ChildrenContainer.Termination)) {
      // do not process normal messages while waiting for all children to terminate
      suspendNonRecursive()
      // do not propagate failures during shutdown to the supervisor
      setFailed()
      if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "stopping"))
    } else finishTerminate()
  }

  final def handleInvokeFailure(t: Throwable, message: String): Unit = {
    publish(Error(t, self.path.toString, clazz(actor), message))
    // prevent any further messages to be processed until the actor has been restarted
    if (!currentlyFailed) try {
      suspendNonRecursive()
      setFailed()
      // suspend children
      val skip: Set[ActorRef] = currentMessage match {
        case Envelope(Failed(`t`), child) ⇒ Set(child)
        case _                            ⇒ Set.empty
      }
      suspendChildren(skip)
      // tell supervisor
      t match { // Wrap InterruptedExceptions and rethrow
        case _: InterruptedException ⇒ parent.tell(Failed(new ActorInterruptedException(t)), self); throw t
        case _                       ⇒ parent.tell(Failed(t), self)
      }
    } catch {
      case NonFatal(e) ⇒
        publish(Error(e, self.path.toString, clazz(actor), "emergency stop: exception in failure handling"))
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

  private def finishRecreate(cause: Throwable, failedActor: Actor): Unit = try {
    try resumeNonRecursive()
    finally setNotFailed() // must happen in any case, so that failure is propagated

    val survivors = children

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
      handleInvokeFailure(new PostRestartException(self, e, cause), e.getMessage)
  }

  final protected def handleFailure(child: ActorRef, cause: Throwable): Unit = getChildByRef(child) match {
    case Some(stats) ⇒ if (!actor.supervisorStrategy.handleFailure(this, child, cause, stats, getAllChildStats)) throw cause
    case None        ⇒ publish(Warning(self.path.toString, clazz(actor), "dropping Failed(" + cause + ") from unknown child " + child))
  }

  final protected def handleChildTerminated(child: ActorRef): Unit = {
    val status = removeChildAndGetStateChange(child)
    /*
     * if this fails, we do nothing in case of terminating/restarting state, 
     * otherwise tell the supervisor etc. (in that second case, the match
     * below will hit the empty default case, too)
     */
    try actor.supervisorStrategy.handleChildTerminated(this, child, children)
    catch {
      case NonFatal(e) ⇒ handleInvokeFailure(e, "handleChildTerminated failed")
    }
    /*
     * if the removal changed the state of the (terminating) children container,
     * then we are continuing the previously suspended recreate/terminate action
     */
    status match {
      case Some(ChildrenContainer.Recreation(cause)) ⇒ finishRecreate(cause, actor)
      case Some(ChildrenContainer.Termination)       ⇒ finishTerminate()
      case _                                         ⇒
    }
  }
}
