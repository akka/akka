/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit

/** Common superclass denoting a persistence effect from an EventSourcedBehavior or a DurableStateBehavior.
 *
 *  From an EventSourcedBehavior, a ChangePersisted will be either an EventPersisted (corresponding to an
 *  Effect.persist or Effect.persistAll) or a StatePersisted (corresponding to a snapshot being persisted).
 *  An EventPersisted can have zero or more tags while a StatePersisted from an EventSourcedBehavior will
 *  always have empty tags.
 *
 *  From a DurableStateBehavior, a ChangePersisted will always be a StatePersisted (corresponding to an
 *  Effect.persist).  The StatePersisted may have a single tag.
 *
 *  EventPersisted can be distinguished from ChangePersisted by a type check (e.g. pattern match or instanceof)
 *  or through the getEvent()/getState() methods: for an EventPersisted, getEvent() will be non-null and getState()
 *  will be null, while for a StatePersisted, getEvent() will be null and getState() will be non-null.
 */
sealed abstract class ChangePersisted[State, +Event]() extends Product with Serializable {
  import akka.util.ccompat.JavaConverters._

  /** Java API
   *  Exactly one of getEvent or getState will be non-null.
   */
  def getEvent(): Event = null.asInstanceOf[Event]

  /** Java API */
  def getSeqNr(): Long = seqNr

  /** Java API
   *  Exactly one of getEvent or getState will be non-null
   */
  def getState(): State = null.asInstanceOf[State]

  /** Java API */
  def getTags(): java.util.Set[String] = tags.asJava

  def seqNr: Long
  def tags: Set[String]
}

final case class EventPersisted[State, Event](event: Event, override val seqNr: Long, override val tags: Set[String])
    extends ChangePersisted[State, Event] {
  override def getEvent(): Event = event
}

final case class StatePersisted[State, Event](state: State, override val seqNr: Long, override val tags: Set[String])
    extends ChangePersisted[State, Event] {
  override def getState(): State = state
}
