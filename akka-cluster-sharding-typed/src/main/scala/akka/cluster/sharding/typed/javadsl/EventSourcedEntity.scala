/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl

import java.util.Optional

import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.javadsl.EventSourcedBehavior

/**
 * Any [[Behavior]] can be used as a sharded entity actor, but the combination of sharding and persistent
 * actors is very common and therefore this `PersistentEntity` class is provided as convenience.
 *
 * It is a [[EventSourcedBehavior]] and is implemented in the same way. It selects the `persistenceId`
 * automatically from the [[EntityTypeKey]] and `entityId` constructor parameters by using
 * [[EntityTypeKey.persistenceIdFrom]].
 */
abstract class EventSourcedEntity[Command, Event, State >: Null] private (
    val entityTypeKey: EntityTypeKey[Command],
    val entityId: String,
    persistenceId: PersistenceId,
    onPersistFailure: Optional[BackoffSupervisorStrategy])
    extends EventSourcedBehavior[Command, Event, State](persistenceId, onPersistFailure) {

  def this(entityTypeKey: EntityTypeKey[Command], entityId: String) = {
    this(
      entityTypeKey,
      entityId,
      persistenceId = entityTypeKey.persistenceIdFrom(entityId),
      Optional.empty[BackoffSupervisorStrategy])
  }

  def this(entityTypeKey: EntityTypeKey[Command], entityId: String, onPersistFailure: BackoffSupervisorStrategy) = {
    this(
      entityTypeKey,
      entityId,
      persistenceId = entityTypeKey.persistenceIdFrom(entityId),
      Optional.ofNullable(onPersistFailure))
  }

}
