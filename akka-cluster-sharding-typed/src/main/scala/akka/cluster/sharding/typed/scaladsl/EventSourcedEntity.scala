/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }

object EventSourcedEntity {

  /**
   * Create a `Behavior` for a persistent actor that is used with Cluster Sharding.
   *
   * Any [[Behavior]] can be used as a sharded entity actor, but the combination of sharding and persistent
   * actors is very common and therefore this `PersistentEntity` is provided as convenience.
   *
   * It is a [[EventSourcedBehavior]] and is implemented in the same way. It selects the `persistenceId`
   * automatically from the [[EntityTypeKey]] and `entityId` constructor parameters by using
   * [[EntityTypeKey.persistenceIdFrom]].
   */
  def apply[Command, Event, State](
      entityTypeKey: EntityTypeKey[Command],
      entityId: String,
      emptyState: State,
      commandHandler: (State, Command) => Effect[Event, State],
      eventHandler: (State, Event) => State): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior(entityTypeKey.persistenceIdFrom(entityId), emptyState, commandHandler, eventHandler)

  /**
   * Create a `Behavior` for a persistent actor that is used with Cluster Sharding
   * and enforces that replies to commands are not forgotten.
   *
   * Then there will be compilation errors if the returned effect isn't a [[ReplyEffect]], which can be
   * created with [[Effect.reply]], [[Effect.noReply]], [[Effect.thenReply]], or [[Effect.thenNoReply]].
   *
   * Any [[Behavior]] can be used as a sharded entity actor, but the combination of sharding and persistent
   * actors is very common and therefore this `PersistentEntity` is provided as convenience.
   *
   * It is a [[EventSourcedBehavior]] and is implemented in the same way. It selects the `persistenceId`
   * automatically from the [[EntityTypeKey]] and `entityId` constructor parameters by using
   * [[EntityTypeKey.persistenceIdFrom]].
   */
  def withEnforcedReplies[Command <: ExpectingReply[_], Event, State](
      entityTypeKey: EntityTypeKey[Command],
      entityId: String,
      emptyState: State,
      commandHandler: (State, Command) => ReplyEffect[Event, State],
      eventHandler: (State, Event) => State): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior.withEnforcedReplies(
      entityTypeKey.persistenceIdFrom(entityId),
      emptyState,
      commandHandler,
      eventHandler)
}
