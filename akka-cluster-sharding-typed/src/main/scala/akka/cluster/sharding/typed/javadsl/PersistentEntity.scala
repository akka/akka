/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl

import java.util.Optional

import scala.compat.java8.OptionConverters._

import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.javadsl.PersistentBehavior

/**
 * Any [[Behavior]] can be used as a sharded entity actor, but the combination of sharding and persistent
 * actors is very common and therefore this `PersistentEntity` class is provided as convenience.
 *
 * It is a [[PersistentBehavior]] and is implemented in the same way. It selects the `persistenceId`
 * automatically from the [[EntityTypeKey]] and `entityId` constructor parameters by using
 * [[EntityTypeKey.persistenceIdFrom]].
 */
abstract class PersistentEntity[Command, Event, State >: Null] private (
  val entityTypeKey: EntityTypeKey[Command],
  persistenceId:     PersistenceId, supervisorStrategy: Optional[BackoffSupervisorStrategy])
  extends PersistentBehavior[Command, Event, State](persistenceId, supervisorStrategy) {

  def this(entityTypeKey: EntityTypeKey[Command], entityId: String) = {
    this(entityTypeKey, persistenceId = entityTypeKey.persistenceIdFrom(entityId), Optional.empty[BackoffSupervisorStrategy])
  }

  def this(entityTypeKey: EntityTypeKey[Command], entityId: String, backoffSupervisorStrategy: BackoffSupervisorStrategy) = {
    this(entityTypeKey, persistenceId = entityTypeKey.persistenceIdFrom(entityId), Optional.ofNullable(backoffSupervisorStrategy))
  }

}
