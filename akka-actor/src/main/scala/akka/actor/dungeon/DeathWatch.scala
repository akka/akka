/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dungeon

import akka.dispatch.sysmsg.{ DeathWatchNotification, Unwatch, Watch }
import akka.event.Logging.{ Debug, Warning }
import akka.actor.{ Actor, ActorCell, ActorRef, ActorRefScope, Address, InternalActorRef, MinimalActorRef, Terminated }
import akka.event.AddressTerminatedTopic
import akka.util.unused

private[akka] trait DeathWatch { this: ActorCell =>

  /**
   * This map holds a [[None]] for actors for which we send a [[Terminated]] notification on termination,
   * ``Some(message)`` for actors for which we send a custom termination message.
   */
  private var watching: Map[ActorRef, Option[Any]] = Map.empty
  private var watchedBy: Set[ActorRef] = ActorCell.emptyActorRefSet
  private var terminatedQueued: Set[ActorRef] = ActorCell.emptyActorRefSet

  def isWatching(ref: ActorRef): Boolean = watching contains ref

  override final def watch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef =>
      if (a != self) {
        if (!watchingContains(a))
          maintainAddressTerminatedSubscription(a) {
            a.sendSystemMessage(Watch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
            updateWatching(a, None)
          } else
          checkWatchingSame(a, None)
      }
      a
  }

  override final def watchWith(subject: ActorRef, msg: Any): ActorRef = subject match {
    case a: InternalActorRef =>
      if (a != self) {
        if (!watchingContains(a))
          maintainAddressTerminatedSubscription(a) {
            a.sendSystemMessage(Watch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
            updateWatching(a, Some(msg))
          } else
          checkWatchingSame(a, Some(msg))
      }
      a
  }

  override final def unwatch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef =>
      if (a != self && watchingContains(a)) {
        a.sendSystemMessage(Unwatch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
        maintainAddressTerminatedSubscription(a) {
          watching = removeFromMap(a, watching)
        }
      }
      terminatedQueued = removeFromSet(a, terminatedQueued)
      a
  }

  protected def receivedTerminated(t: Terminated): Unit =
    if (terminatedQueued(t.actor)) {
      terminatedQueued -= t.actor // here we know that it is the SAME ref which was put in
      receiveMessage(t)
    }

  /**
   * When this actor is watching the subject of [[akka.actor.Terminated]] message
   * it will be propagated to user's receive.
   */
  protected def watchedActorTerminated(
      actor: ActorRef,
      existenceConfirmed: Boolean,
      addressTerminated: Boolean): Unit = {
    watchingGet(actor) match {
      case None => // We're apparently no longer watching this actor.
      case Some(optionalMessage) =>
        maintainAddressTerminatedSubscription(actor) {
          watching = removeFromMap(actor, watching)
        }
        if (!isTerminating) {
          self.tell(optionalMessage.getOrElse(Terminated(actor)(existenceConfirmed, addressTerminated)), actor)
          terminatedQueuedFor(actor)
        }
    }
    if (childrenRefs.getByRef(actor).isDefined) handleChildTerminated(actor)
  }

  private[akka] def terminatedQueuedFor(subject: ActorRef): Unit =
    terminatedQueued += subject

  // TODO this should be removed and be replaced with `watching.contains(subject)`
  //   when all actor references have uid, i.e. actorFor is removed
  private def watchingContains(subject: ActorRef): Boolean =
    watching.contains(subject) || (subject.path.uid != ActorCell.undefinedUid &&
    watching.contains(new UndefinedUidActorRef(subject)))

  // TODO this should be removed and be replaced with `watching.get(subject)`
  //   when all actor references have uid, i.e. actorFor is removed
  // Returns None when the subject is not being watched.
  // If the subject is being matched, the inner option is the optional custom termination
  // message that should be sent instead of the default Terminated.
  private def watchingGet(subject: ActorRef): Option[Option[Any]] =
    watching
      .get(subject)
      .orElse(
        if (subject.path.uid == ActorCell.undefinedUid) None
        else watching.get(new UndefinedUidActorRef(subject)))

  // TODO this should be removed and be replaced with `set - subject`
  //   when all actor references have uid, i.e. actorFor is removed
  private def removeFromSet(subject: ActorRef, set: Set[ActorRef]): Set[ActorRef] =
    if (subject.path.uid != ActorCell.undefinedUid) (set - subject) - new UndefinedUidActorRef(subject)
    else set.filterNot(_.path == subject.path)

  // TODO this should be removed and be replaced with `set - subject`
  //   when all actor references have uid, i.e. actorFor is removed
  private def removeFromMap[T](subject: ActorRef, map: Map[ActorRef, T]): Map[ActorRef, T] =
    if (subject.path.uid != ActorCell.undefinedUid) (map - subject) - new UndefinedUidActorRef(subject)
    else map.filterKeys(_.path != subject.path).toMap

  private def updateWatching(ref: InternalActorRef, newMessage: Option[Any]): Unit =
    watching = watching.updated(ref, newMessage)

  /** Call only if it was checked before that `watching contains ref` */
  private def checkWatchingSame(ref: InternalActorRef, newMessage: Option[Any]): Unit = {
    val previous = watching.get(ref).get
    if (previous != newMessage)
      throw new IllegalStateException(
        s"Watch($self, $ref) termination message was not overwritten from [$previous] to [$newMessage]. " +
        s"If this was intended, unwatch first before using `watch` / `watchWith` with another message.")
  }

  protected def tellWatchersWeDied(): Unit =
    if (!watchedBy.isEmpty) {
      try {
        // Don't need to send to parent parent since it receives a DWN by default
        def sendTerminated(ifLocal: Boolean)(watcher: ActorRef): Unit =
          if (watcher.asInstanceOf[ActorRefScope].isLocal == ifLocal && watcher != parent)
            watcher
              .asInstanceOf[InternalActorRef]
              .sendSystemMessage(DeathWatchNotification(self, existenceConfirmed = true, addressTerminated = false))

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
        watchedBy.foreach(sendTerminated(ifLocal = false))
        watchedBy.foreach(sendTerminated(ifLocal = true))
      } finally {
        maintainAddressTerminatedSubscription() {
          watchedBy = ActorCell.emptyActorRefSet
        }
      }
    }

  protected def unwatchWatchedActors(@unused actor: Actor): Unit =
    if (!watching.isEmpty) {
      maintainAddressTerminatedSubscription() {
        try {
          watching.foreach { // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
            case (watchee: InternalActorRef, _) => watchee.sendSystemMessage(Unwatch(watchee, self))
            case (watchee, _)                   =>
              // should never happen, suppress "match may not be exhaustive" compiler warning
              throw new IllegalStateException(s"Expected InternalActorRef, but got [${watchee.getClass.getName}]")
          }
        } finally {
          watching = Map.empty
          terminatedQueued = ActorCell.emptyActorRefSet
        }
      }
    }

  protected def addWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
    val watcheeSelf = watchee == self
    val watcherSelf = watcher == self

    if (watcheeSelf && !watcherSelf) {
      if (!watchedBy.contains(watcher)) maintainAddressTerminatedSubscription(watcher) {
        watchedBy += watcher
        if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), s"now watched by $watcher"))
      }
    } else if (!watcheeSelf && watcherSelf) {
      watch(watchee)
    } else {
      publish(
        Warning(self.path.toString, clazz(actor), "BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, self)))
    }
  }

  protected def remWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
    val watcheeSelf = watchee == self
    val watcherSelf = watcher == self

    if (watcheeSelf && !watcherSelf) {
      if (watchedBy.contains(watcher)) maintainAddressTerminatedSubscription(watcher) {
        watchedBy -= watcher
        if (system.settings.DebugLifecycle)
          publish(Debug(self.path.toString, clazz(actor), s"no longer watched by $watcher"))
      }
    } else if (!watcheeSelf && watcherSelf) {
      unwatch(watchee)
    } else {
      publish(
        Warning(self.path.toString, clazz(actor), "BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, self)))
    }
  }

  protected def addressTerminated(address: Address): Unit = {
    // cleanup watchedBy since we know they are dead
    maintainAddressTerminatedSubscription() {
      for (a <- watchedBy; if a.path.address == address) watchedBy -= a
    }

    // send DeathWatchNotification to self for all matching subjects
    // that are not child with existenceConfirmed = false because we could have been watching a
    // non-local ActorRef that had never resolved before the other node went down
    // When a parent is watching a child and it terminates due to AddressTerminated
    // it is removed by sending DeathWatchNotification with existenceConfirmed = true to support
    // immediate creation of child with same name.
    for ((a, _) <- watching; if a.path.address == address) {
      self.sendSystemMessage(
        DeathWatchNotification(a, existenceConfirmed = childrenRefs.getByRef(a).isDefined, addressTerminated = true))
    }
  }

  /**
   * Starts subscription to AddressTerminated if not already subscribing and the
   * block adds a non-local ref to watching or watchedBy.
   * Ends subscription to AddressTerminated if subscribing and the
   * block removes the last non-local ref from watching and watchedBy.
   */
  private def maintainAddressTerminatedSubscription[T](change: ActorRef = null)(block: => T): T = {
    def isNonLocal(ref: ActorRef) = ref match {
      case null                              => true
      case a: InternalActorRef if !a.isLocal => true
      case _                                 => false
    }

    if (isNonLocal(change)) {
      def hasNonLocalAddress: Boolean = ((watching.keysIterator.exists(isNonLocal)) || (watchedBy.exists(isNonLocal)))
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

  private def unsubscribeAddressTerminated(): Unit = AddressTerminatedTopic(system).unsubscribe(self)

  private def subscribeAddressTerminated(): Unit = AddressTerminatedTopic(system).subscribe(self)

}

private[akka] class UndefinedUidActorRef(ref: ActorRef) extends MinimalActorRef {
  override val path = ref.path.withUid(ActorCell.undefinedUid)
  override def provider = throw new UnsupportedOperationException("UndefinedUidActorRef does not provide")
}
