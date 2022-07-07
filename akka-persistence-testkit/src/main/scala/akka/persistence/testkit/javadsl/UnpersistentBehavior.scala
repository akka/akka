/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import akka.actor.typed.Behavior
import akka.persistence.testkit.ChangePersisted
import akka.persistence.testkit.internal.Unpersistent

import java.util.concurrent.ConcurrentLinkedQueue

object UnpersistentBehavior {

  /** Given an EventSourcedBehavior, produce a non-persistent Behavior which synchronously publishes events and snapshots
   *  for inspection.  State is updated as in the EventSourcedBehavior, and side effects are performed synchronously.  The
   *  resulting Behavior is, contingent on the command handling, event handling, and side effects being compatible with the
   *  BehaviorTestKit, testable with the BehaviorTestKit.
   *
   *  The returned Behavior does not intrinsically depend on configuration: it therefore does not serialize and
   *  assumes an unbounded stash for commands.
   *
   *  @param behavior a (possibly wrapped) EventSourcedBehavior to serve as the basis for the unpersistent behavior
   *  @param fromState start the unpersistent behavior with this state; if null, behavior's initialState will be used
   *  @param fromOffset start the unpersistent behavior with this offset; only applies if fromState is non-null
   *  @return an UnpersistentBehavior based on an EventSourcedBehavior
   */
  def fromEventSourced[Command, Event, State](
      behavior: Behavior[Command],
      fromState: State,
      fromOffset: Long): UnpersistentBehavior[Command, Event, State] = {
    require(fromOffset >= 0, "fromOffset must be at least zero")

    val fromStateAndOffset = Option(fromState).map(_ -> fromOffset)
    val (b, q) = Unpersistent.eventSourced[Command, Event, State](behavior, fromStateAndOffset)
    new UnpersistentBehavior(b, q)
  }

  def fromEventSourced[Command, Event, State](
      behavior: Behavior[Command]): UnpersistentBehavior[Command, Event, State] =
    fromEventSourced(behavior, null.asInstanceOf[State], 0)

  def fromDurableState[Command, State](
    behavior: Behavior[Command],
    fromState: State
  ): UnpersistentBehavior[Command, Void, State] = {
    val (b, q) = Unpersistent.durableState(behavior, Option(fromState))
    new UnpersistentBehavior(b, q.asInstanceOf[ConcurrentLinkedQueue[ChangePersisted[State, Void]]])
  }

  def fromDurableState[Command, State](
    behavior: Behavior[Command]
  ): UnpersistentBehavior[Command, Void, State] =
    fromDurableState(behavior, null.asInstanceOf[State])
}

class UnpersistentBehavior[Command, Event, State] private (
    behavior: Behavior[Command],
    changeQueue: ConcurrentLinkedQueue[ChangePersisted[State, Event]]) {
  def getBehavior(): Behavior[Command] = behavior

  def getChangeQueue(): ConcurrentLinkedQueue[ChangePersisted[State, Event]] = changeQueue
}
