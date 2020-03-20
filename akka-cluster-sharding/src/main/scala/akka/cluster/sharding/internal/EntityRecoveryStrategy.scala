/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion
import akka.util.PrettyDuration

import scala.collection.immutable.Set
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object EntityRecoveryStrategy {
  def allStrategy(): EntityRecoveryStrategy = new AllAtOnceEntityRecoveryStrategy()

  def constantStrategy(
      actorSystem: ActorSystem,
      frequency: FiniteDuration,
      numberOfEntities: Int): EntityRecoveryStrategy =
    new ConstantRateEntityRecoveryStrategy(actorSystem, frequency, numberOfEntities)
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] trait EntityRecoveryStrategy {

  import ShardRegion.EntityId

  import scala.concurrent.Future

  def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]]
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class AllAtOnceEntityRecoveryStrategy extends EntityRecoveryStrategy {

  import ShardRegion.EntityId

  override def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]] =
    if (entities.isEmpty) Set.empty else Set(Future.successful(entities))

  override def toString: EntityId = "AllAtOnceEntityRecoveryStrategy"
}

final class ConstantRateEntityRecoveryStrategy(
    actorSystem: ActorSystem,
    frequency: FiniteDuration,
    numberOfEntities: Int)
    extends EntityRecoveryStrategy {

  import ShardRegion.EntityId
  import actorSystem.dispatcher
  import akka.pattern.after

  override def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]] =
    entities
      .grouped(numberOfEntities)
      .foldLeft((frequency, Set[Future[Set[EntityId]]]())) {
        case ((interval, scheduledEntityIds), entityIds) =>
          (interval + frequency, scheduledEntityIds + scheduleEntities(interval, entityIds))
      }
      ._2

  private def scheduleEntities(interval: FiniteDuration, entityIds: Set[EntityId]): Future[Set[EntityId]] =
    after(interval, actorSystem.scheduler)(Future.successful[Set[EntityId]](entityIds))

  override def toString: EntityId =
    s"ConstantRateEntityRecoveryStrategy(${PrettyDuration.format(frequency)}, $numberOfEntities)"
}
