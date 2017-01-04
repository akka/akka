/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.typed
package internal

import akka.event.Logging.{ Warning, Debug }
import akka.event.AddressTerminatedTopic
import akka.event.Logging
import akka.actor.Address

/*
 * THOUGHTS
 *
 * - an ActorRef is a channel that allows sending messages — in particular it is NOT a sender!
 * - a channel is scoped by the session it is part of
 * - termination means that the session ends because sending further messages is pointless
 * - this means that there is no ordering requirement between Terminated and any other received message
 */
private[typed] trait DeathWatch[T] {

  /*
   * INTERFACE WITH ACTORCELL
   */
  protected def system: ActorSystem[Nothing]
  protected def self: ActorRefImpl[T]
  protected def parent: ActorRefImpl[Nothing]
  protected def behavior: Behavior[T]
  protected def next(b: Behavior[T], msg: Any): Unit
  protected def childrenMap: Map[String, ActorRefImpl[Nothing]]
  protected def terminatingMap: Map[String, ActorRefImpl[Nothing]]
  protected def isTerminating: Boolean
  protected def ctx: ActorContext[T]
  protected def maySend: Boolean
  protected def publish(e: Logging.LogEvent): Unit
  protected def clazz(obj: AnyRef): Class[_]

  protected def removeChild(actor: ActorRefImpl[Nothing]): Unit
  protected def finishTerminate(): Unit

  type ARImpl = ActorRefImpl[Nothing]

  private var watching = Set.empty[ARImpl]
  private var watchedBy = Set.empty[ARImpl]

  final def watch[U](_a: ActorRef[U]): ActorRef[U] = {
    val a = _a.sorry
    if (a != self && !watching.contains(a)) {
      maintainAddressTerminatedSubscription(a) {
        a.sendSystem(Watch(a, self))
        watching += a
      }
    }
    a
  }

  final def unwatch[U](_a: ActorRef[U]): ActorRef[U] = {
    val a = _a.sorry
    if (a != self && watching.contains(a)) {
      a.sendSystem(Unwatch(a, self))
      maintainAddressTerminatedSubscription(a) {
        watching -= a
      }
    }
    a
  }

  /**
   * When this actor is watching the subject of [[akka.actor.Terminated]] message
   * it will be propagated to user's receive.
   */
  protected def watchedActorTerminated(actor: ARImpl, failure: Throwable): Boolean = {
    removeChild(actor)
    if (watching.contains(actor)) {
      maintainAddressTerminatedSubscription(actor) {
        watching -= actor
      }
      if (maySend) {
        val t = Terminated(actor)(failure)
        next(behavior.management(ctx, t), t)
      }
    }
    if (isTerminating && terminatingMap.isEmpty) {
      finishTerminate()
      false
    } else true
  }

  protected def tellWatchersWeDied(): Unit =
    if (watchedBy.nonEmpty) {
      try {
        // Don't need to send to parent parent since it receives a DWN by default
        def sendTerminated(ifLocal: Boolean)(watcher: ARImpl): Unit =
          if (watcher.isLocal == ifLocal && watcher != parent) watcher.sendSystem(DeathWatchNotification(self, null))

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
      } finally {
        maintainAddressTerminatedSubscription() {
          watchedBy = Set.empty
        }
      }
    }

  protected def unwatchWatchedActors(): Unit =
    if (watching.nonEmpty) {
      maintainAddressTerminatedSubscription() {
        try {
          watching.foreach(watchee ⇒ watchee.sendSystem(Unwatch(watchee, self)))
        } finally {
          watching = Set.empty
        }
      }
    }

  protected def addWatcher(watchee: ARImpl, watcher: ARImpl): Unit = {
    val watcheeSelf = watchee == self
    val watcherSelf = watcher == self

    if (watcheeSelf && !watcherSelf) {
      if (!watchedBy.contains(watcher)) maintainAddressTerminatedSubscription(watcher) {
        watchedBy += watcher
        if (system.settings.untyped.DebugLifecycle) publish(Debug(self.path.toString, clazz(behavior), s"now watched by $watcher"))
      }
    } else if (!watcheeSelf && watcherSelf) {
      watch[Nothing](watchee)
    } else {
      publish(Warning(self.path.toString, clazz(behavior), "BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, self)))
    }
  }

  protected def remWatcher(watchee: ARImpl, watcher: ARImpl): Unit = {
    val watcheeSelf = watchee == self
    val watcherSelf = watcher == self

    if (watcheeSelf && !watcherSelf) {
      if (watchedBy.contains(watcher)) maintainAddressTerminatedSubscription(watcher) {
        watchedBy -= watcher
        if (system.settings.untyped.DebugLifecycle) publish(Debug(self.path.toString, clazz(behavior), s"no longer watched by $watcher"))
      }
    } else if (!watcheeSelf && watcherSelf) {
      unwatch[Nothing](watchee)
    } else {
      publish(Warning(self.path.toString, clazz(behavior), "BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, self)))
    }
  }

  protected def addressTerminated(address: Address): Unit = {
    // cleanup watchedBy since we know they are dead
    maintainAddressTerminatedSubscription() {
      for (a ← watchedBy; if a.path.address == address) watchedBy -= a
    }

    for (a ← watching; if a.path.address == address) {
      self.sendSystem(DeathWatchNotification(a, null))
    }
  }

  /**
   * Starts subscription to AddressTerminated if not already subscribing and the
   * block adds a non-local ref to watching or watchedBy.
   * Ends subscription to AddressTerminated if subscribing and the
   * block removes the last non-local ref from watching and watchedBy.
   */
  private def maintainAddressTerminatedSubscription[U](change: ARImpl = null)(block: ⇒ U): U = {
    def isNonLocal(ref: ARImpl) = ref match {
      case null ⇒ true
      case a    ⇒ !a.isLocal
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

  // FIXME: these will need to be redone once remoting is integrated
  private def unsubscribeAddressTerminated(): Unit = ???
  private def subscribeAddressTerminated(): Unit = ???

}
