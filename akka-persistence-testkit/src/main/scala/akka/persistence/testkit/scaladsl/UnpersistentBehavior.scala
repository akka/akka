/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.typed.Behavior
import akka.persistence.testkit.internal.{ Unpersistent, PersistenceProbeImpl }

import scala.reflect.ClassTag

object UnpersistentBehavior {
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
      initialStateAndSequenceNr: Option[(State, Long)] = None): (Behavior[Command], PersistenceProbe[Event], PersistenceProbe[State]) = {
    val eventProbe = new PersistenceProbeImpl[Event]
    val snapshotProbe = new PersistenceProbeImpl[State]
    val resultingBehavior =
      Unpersistent.eventSourced(behavior, initialStateAndSequenceNr) { (event: Event, sequenceNr: Long, tags: Set[String]) =>
          eventProbe.persist((event, sequenceNr, tags))
        } { (snapshot, sequenceNr) =>
          snapshotProbe.persist((snapshot, sequenceNr, Set.empty))
        }

    (resultingBehavior, eventProbe.asScala, snapshotProbe.asScala)
  }

  def fromEventSourced[Command, Event, State](
    behavior: Behavior[Command],
    initialState: State): (Behavior[Command], PersistenceProbe[Event], PersistenceProbe[State]) =
    fromEventSourced(behavior, Some(initialState -> 0L))

  def fromDurableState[Command, State](
      behavior: Behavior[Command],
      initialState: Option[State] = None): (Behavior[Command], PersistenceProbe[State]) = {
    val probe = new PersistenceProbeImpl[State]
    
    Unpersistent.durableState(behavior, initialState) { (state, version, tag) =>
      probe.persist((state, version, if (tag.isEmpty) Set.empty else Set(tag)))
    } -> probe.asScala
  }
}

case class PersistenceEffect[T](persistedObject: T, sequenceNr: Long, tags: Set[String])

trait PersistenceProbe[T] {
  /** Collect all persistence effects from the probe and empty the probe */
  def drain(): Seq[(PersistenceEffect[T])]

  /** Get and remove the oldest persistence effect from the probe */
  def extract(): (PersistenceEffect[T])

  /** Get and remove the oldest persistence effect from the probe, failing if the
   *  persisted object is not of the requested type
   */
  def expectPersistedType[S <: T : ClassTag](): PersistenceEffect[S]

  /** Are there any persistence effects? */
  def hasEffects: Boolean

  /** Assert that the given object was persisted in the oldest persistence effect and
   *  remove that persistence effect
   */
  def expectPersisted(obj: T): PersistenceProbe[T]

  /** Assert that the given object was persisted with the given tag in the oldest
   *  persistence effect and remove that persistence effect.  If the persistence
   *  effect has multiple tags, only one of them has to match in order for the
   *  assertion to succeed.
   */
  def expectPersisted(obj: T, tag: String): PersistenceProbe[T]

  /** Assert that the given object was persisted with the given tags in the oldest
   *  persistence effect and remove that persistence effect.  If the persistence
   *  effect has tags which are not given, the assertion fails.
   */
  def expectPersisted(obj: T, tags: Set[String]): PersistenceProbe[T]
}
