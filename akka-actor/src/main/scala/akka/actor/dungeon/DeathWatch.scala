/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.dungeon

import akka.actor.{ Terminated, InternalActorRef, ActorRef, ActorRefScope, ActorCell, Actor, Address, AddressTerminated }
import akka.dispatch.sysmsg.{ DeathWatchNotification, Watch, Unwatch }
import akka.event.Logging.{ Warning, Error, Debug }
import scala.util.control.NonFatal
import akka.actor.MinimalActorRef

private[akka] trait DeathWatch { this: ActorCell ⇒

  private var watching: Set[ActorRef] = ActorCell.emptyActorRefSet
  private var watchedBy: Set[ActorRef] = ActorCell.emptyActorRefSet
  private var terminatedQueued: Set[ActorRef] = ActorCell.emptyActorRefSet

  override final def watch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef ⇒
      if (a != self && !watchingContains(a)) {
        maintainAddressTerminatedSubscription(a) {
          a.sendSystemMessage(Watch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          watching += a
        }
      }
      a
  }

  override final def unwatch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef ⇒
      if (a != self && watchingContains(a)) {
        a.sendSystemMessage(Unwatch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
        maintainAddressTerminatedSubscription(a) {
          watching = removeFromSet(a, watching)
        }
      }
      terminatedQueued = removeFromSet(a, terminatedQueued)
      a
  }

  protected def receivedTerminated(t: Terminated): Unit = {
    if (terminatedQueued(t.actor)) {
      terminatedQueued -= t.actor // here we know that it is the SAME ref which was put in
      receiveMessage(t)
    }
  }

  /**
   * When this actor is watching the subject of [[akka.actor.Terminated]] message
   * it will be propagated to user's receive.
   */
  protected def watchedActorTerminated(actor: ActorRef, existenceConfirmed: Boolean, addressTerminated: Boolean): Unit = {
    if (childrenRefs.getByRef(actor).isDefined) handleChildTerminated(actor)
    if (watchingContains(actor)) {
      maintainAddressTerminatedSubscription(actor) {
        watching = removeFromSet(actor, watching)
      }
      if (!isTerminating) {
        self.tell(Terminated(actor)(existenceConfirmed, addressTerminated), actor)
        terminatedQueued += actor
      }
    }
  }

  // TODO this should be removed and be replaced with `watching.contains(subject)`
  //   when all actor references have uid, i.e. actorFor is removed
  private def watchingContains(subject: ActorRef): Boolean = {
    watching.contains(subject) || (subject.path.uid != ActorCell.undefinedUid &&
      watching.contains(new UndefinedUidActorRef(subject)))
  }

  // TODO this should be removed and be replaced with `set - subject`
  //   when all actor references have uid, i.e. actorFor is removed
  private def removeFromSet(subject: ActorRef, set: Set[ActorRef]): Set[ActorRef] = {
    val removed = if (set(subject)) set - subject else set
    if (subject.path.uid != ActorCell.undefinedUid)
      removed - new UndefinedUidActorRef(subject)
    else removed filterNot (_.path == subject.path)
  }

  protected def tellWatchersWeDied(actor: Actor): Unit = {
    if (!watchedBy.isEmpty) {
      try {
        def sendTerminated(ifLocal: Boolean)(watcher: ActorRef): Unit =
          if (watcher.asInstanceOf[ActorRefScope].isLocal == ifLocal) watcher.asInstanceOf[InternalActorRef].sendSystemMessage(
            DeathWatchNotification(self, existenceConfirmed = true, addressTerminated = false))

        /*
         * It is important to notify the remote watchers first, otherwise RemoteDaemon might shut down, causing
         * the remoting to shut down as well. At this point Terminated messages to remote watchers are no longer
         * deliverable.
         *
         * The problematic case is:
         *  1. Terminated is sent to RemoteDaemon
         *   1a. RemoteDaemon is fast enough to notify the terminator actor in RemoteActorRefProvider
         *   1b. The terminator is fast enough to enqueue the shutdown command in the remoting
         *  2. Only at this point is the Terminated (to be sent remotely) enqueued in the mailbox of remoting
         *
         * If the remote watchers are notified first, then the mailbox of the Remoting will guarantee the correct order.
         */
        watchedBy foreach sendTerminated(ifLocal = false)
        watchedBy foreach sendTerminated(ifLocal = true)
      } finally watchedBy = ActorCell.emptyActorRefSet
    }
  }

  protected def unwatchWatchedActors(actor: Actor): Unit = {
    if (!watching.isEmpty) {
      try {
        watching foreach { // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          case watchee: InternalActorRef ⇒ watchee.sendSystemMessage(Unwatch(watchee, self))
        }
      } finally {
        watching = ActorCell.emptyActorRefSet
        terminatedQueued = ActorCell.emptyActorRefSet
        unsubscribeAddressTerminated()
      }
    }
  }

  protected def addWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
    val watcheeSelf = watchee == self
    val watcherSelf = watcher == self

    if (watcheeSelf && !watcherSelf) {
      if (!watchedBy.contains(watcher)) maintainAddressTerminatedSubscription(watcher) {
        watchedBy += watcher
        if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "now monitoring " + watcher))
      }
    } else if (!watcheeSelf && watcherSelf) {
      watch(watchee)
    } else {
      publish(Warning(self.path.toString, clazz(actor), "BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, self)))
    }
  }

  protected def remWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
    val watcheeSelf = watchee == self
    val watcherSelf = watcher == self

    if (watcheeSelf && !watcherSelf) {
      if (watchedBy.contains(watcher)) maintainAddressTerminatedSubscription(watcher) {
        watchedBy -= watcher
        if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "stopped monitoring " + watcher))
      }
    } else if (!watcheeSelf && watcherSelf) {
      unwatch(watchee)
    } else {
      publish(Warning(self.path.toString, clazz(actor), "BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, self)))
    }
  }

  protected def addressTerminated(address: Address): Unit = {
    // cleanup watchedBy since we know they are dead
    maintainAddressTerminatedSubscription() {
      for (a ← watchedBy; if a.path.address == address) watchedBy -= a
    }

    // send DeathWatchNotification to self for all matching subjects
    // that are not child with existenceConfirmed = false because we could have been watching a
    // non-local ActorRef that had never resolved before the other node went down
    // When a parent is watching a child and it terminates due to AddressTerminated
    // it is removed by sending DeathWatchNotification with existenceConfirmed = true to support
    // immediate creation of child with same name.
    for (a ← watching; if a.path.address == address) {
      self.sendSystemMessage(DeathWatchNotification(a, existenceConfirmed = childrenRefs.getByRef(a).isDefined, addressTerminated = true))
    }
  }

  /**
   * Starts subscription to AddressTerminated if not already subscribing and the
   * block adds a non-local ref to watching or watchedBy.
   * Ends subscription to AddressTerminated if subscribing and the
   * block removes the last non-local ref from watching and watchedBy.
   */
  private def maintainAddressTerminatedSubscription[T](change: ActorRef = null)(block: ⇒ T): T = {
    def isNonLocal(ref: ActorRef) = ref match {
      case null                              ⇒ true
      case a: InternalActorRef if !a.isLocal ⇒ true
      case _                                 ⇒ false
    }

    if (isNonLocal(change)) {
      def hasNonLocalAddress: Boolean = ((watching exists isNonLocal) || (watchedBy exists isNonLocal))
      val had = hasNonLocalAddress
      val result = block
      val has = hasNonLocalAddress
      if (had && !has) unsubscribeAddressTerminated()
      else if (!had && has) subscribeAddressTerminated()
      result
    } else {
      block
    }
  }

  private def unsubscribeAddressTerminated(): Unit = system.eventStream.unsubscribe(self, classOf[AddressTerminated])

  private def subscribeAddressTerminated(): Unit = system.eventStream.subscribe(self, classOf[AddressTerminated])

}

private[akka] class UndefinedUidActorRef(ref: ActorRef) extends MinimalActorRef {
  override val path = ref.path.withUid(ActorCell.undefinedUid)
  override def provider = throw new UnsupportedOperationException("UndefinedUidActorRef does not provide")
}
