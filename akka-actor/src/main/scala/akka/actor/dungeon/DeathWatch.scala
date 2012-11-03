/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.dungeon

import akka.actor.{ Terminated, InternalActorRef, ActorRef, ActorCell, Actor, Address, AddressTerminated }
import akka.dispatch.{ Watch, Unwatch }
import akka.event.Logging.{ Warning, Error, Debug }
import scala.util.control.NonFatal

private[akka] trait DeathWatch { this: ActorCell ⇒

  private var watching: Set[ActorRef] = ActorCell.emptyActorRefSet
  private var watchedBy: Set[ActorRef] = ActorCell.emptyActorRefSet

  override final def watch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef ⇒
      if (a != self && !watching.contains(a)) {
        maintainAddressTerminatedSubscription(a) {
          a.sendSystemMessage(Watch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          watching += a
        }
      }
      a
  }

  override final def unwatch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef ⇒
      if (a != self && watching.contains(a)) {
        maintainAddressTerminatedSubscription(a) {
          a.sendSystemMessage(Unwatch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          watching -= a
        }
      }
      a
  }

  /**
   * When this actor is watching the subject of [[akka.actor.Terminated]] message
   * it will be propagated to user's receive.
   */
  protected def watchedActorTerminated(t: Terminated): Unit = if (watching.contains(t.actor)) {
    maintainAddressTerminatedSubscription(t.actor) {
      watching -= t.actor
    }
    receiveMessage(t)
  }

  protected def tellWatchersWeDied(actor: Actor): Unit = {
    if (!watchedBy.isEmpty) {
      val terminated = Terminated(self)(existenceConfirmed = true, addressTerminated = false)
      try {
        watchedBy foreach {
          watcher ⇒
            try watcher.tell(terminated, self) catch {
              case NonFatal(t) ⇒ publish(Error(t, self.path.toString, clazz(actor), "deathwatch"))
            }
        }
      } finally watchedBy = ActorCell.emptyActorRefSet
    }
  }

  protected def unwatchWatchedActors(actor: Actor): Unit = {
    if (!watching.isEmpty) {
      try {
        watching foreach { // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          case watchee: InternalActorRef ⇒ try watchee.sendSystemMessage(Unwatch(watchee, self)) catch {
            case NonFatal(t) ⇒ publish(Error(t, self.path.toString, clazz(actor), "deathwatch"))
          }
        }
      } finally {
        watching = ActorCell.emptyActorRefSet
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

    // send Terminated to self for all matching subjects
    // existenceConfirmed = false because we could have been watching a
    // non-local ActorRef that had never resolved before the other node went down
    for (a ← watching; if a.path.address == address) {
      self ! Terminated(a)(existenceConfirmed = false, addressTerminated = true)
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