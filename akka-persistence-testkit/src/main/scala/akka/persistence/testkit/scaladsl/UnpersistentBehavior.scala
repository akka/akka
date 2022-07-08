/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.typed.Behavior
import akka.persistence.testkit.ChangePersisted
import akka.persistence.testkit.internal.Unpersistent

import java.util.concurrent.ConcurrentLinkedQueue

object UnpersistentBehavior {

  type BehaviorAndChanges[Command, Event, State] =
    (Behavior[Command], ConcurrentLinkedQueue[ChangePersisted[State, Event]])

  /** Given an EventSourcedBehavior, produce a non-persistent Behavior which synchronously publishes events and snapshots
   *  for inspection.  State is updated as in the EventSourcedBehavior, and side effects are performed synchronously.  The
   *  resulting Behavior is, contingent on the command handling, event handling, and side effects being compatible with the
   *  BehaviorTestKit, testable with the BehaviorTestKit.
   *
   *  The returned Behavior does not intrinsically depend on configuration: it therefore does not serialize and assumes an
   *  unbounded stash for commands.
   */
  def fromEventSourced[Command, Event, State](
      behavior: Behavior[Command],
      fromStateAndOffset: Option[(State, Long)] = None): BehaviorAndChanges[Command, Event, State] =
    Unpersistent.eventSourced(behavior, fromStateAndOffset)

  def fromDurableState[Command, State](
      behavior: Behavior[Command],
      fromState: Option[State] = None): BehaviorAndChanges[Command, Nothing, State] =
    Unpersistent.durableState(behavior, fromState)

  /** Builds a List with all elements from the queue.  This is intended to facilitate tests of an Event Sourced or
   *  Durable State behavior, so it's assumed that there are "not that many" elements in the queue.
   */
  def drainChangesToList[State, Event](
      queue: ConcurrentLinkedQueue[ChangePersisted[State, Event]]): List[ChangePersisted[State, Event]] = {
    type T = ChangePersisted[State, Event]

    @annotation.tailrec
    def iter(acc: List[T]): List[T] = {
      val t = queue.poll()
      if (t == null) acc else iter(t :: acc)
    }

    iter(Nil).reverse
  }
}
