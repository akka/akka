/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import java.util.concurrent.ConcurrentHashMap

import akka.Done
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetDataDeleted
import akka.cluster.ddata.Replicator.GetFailure
import akka.cluster.ddata.Replicator.GetResponse
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.ModifyFailure
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.StoreFailure
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateDataDeleted
import akka.cluster.ddata.Replicator.UpdateResponse
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId
import akka.dispatch.ExecutionContexts
import akka.pattern._
import akka.util.PrettyDuration
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class DDataRememberEntities(
    system: ActorSystem,
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends RememberEntitiesShardStore {

  import system.dispatcher
  implicit private val node = Cluster(system)
  implicit private val selfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  // The default maximum-frame-size is 256 KiB with Artery.
  // When using entity identifiers with 36 character strings (e.g. UUID.randomUUID).
  // By splitting the elements over 5 keys we can support 10000 entities per shard.
  // The Gossip message size of 5 ORSet with 2000 ids is around 200 KiB.
  // This is by intention not configurable because it's important to have the same
  // configuration on each node.
  private val numberOfKeys = 5
  private val _stateKeys = new ConcurrentHashMap[ShardId, Array[ORSetKey[EntityId]]]()
  private def stateKeys(shardId: ShardId): Array[ORSetKey[EntityId]] =
    _stateKeys.computeIfAbsent(
      shardId,
      shardId => Array.tabulate(numberOfKeys)(i => ORSetKey[EntityId](s"shard-$typeName-$shardId-$i")))

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)
  // FIXME retrying writes?
  // private val maxUpdateAttempts = 3
  // FIXME configurable
  private implicit val askTimeout: Timeout = 10.seconds

  private def key(shardId: ShardId, entityId: EntityId): ORSetKey[EntityId] = {
    val i = math.abs(entityId.hashCode % numberOfKeys)
    stateKeys(shardId)(i)
  }

  override def addEntity(shardId: ShardId, entityId: EntityId): Future[Done] = {
    (replicator ? Update(key(shardId, entityId), ORSet.empty[EntityId], writeMajority) { existing =>
      existing :+ entityId
    }).mapTo[UpdateResponse[ORSet[EntityId]]].map(doneOrFail)
  }

  override def removeEntity(shardId: ShardId, entityId: EntityId): Future[Done] = {
    (replicator ? Update(key(shardId, entityId), ORSet.empty[EntityId], writeMajority) { existing =>
      existing.remove(entityId)
    }).mapTo[UpdateResponse[ORSet[EntityId]]].map(doneOrFail)
  }

  private def doneOrFail(res: UpdateResponse[ORSet[EntityId]]): Done = res match {
    case UpdateSuccess(_, _) =>
      Done
    case UpdateTimeout(_, _) =>
      throw new RuntimeException(
        s"Unable to update state, within 'updating-state-timeout'= ${PrettyDuration.format(writeMajority.timeout)}")
    case StoreFailure(_, _) =>
      throw new RuntimeException("Unable to update state, due to store failure")
    case ModifyFailure(_, error, cause, _) =>
      throw new RuntimeException(s"Unable to update state, due to modify failure: $error", cause)
    case UpdateDataDeleted(_, _) =>
      throw new RuntimeException(s"Unable to update state, due to delete")
  }

  override def getEntities(shardId: ShardId): Future[Set[EntityId]] = {
    import system.dispatcher
    val responsePerKey = (0 until numberOfKeys).toSet[Int].map { i =>
      val key = stateKeys(shardId)(i)
      (replicator ? Get(key, readMajority, Some(i))).mapTo[GetResponse[ORSet[ShardId]]].map {
        case success @ GetSuccess(_, _) =>
          success.dataValue.elements
        case NotFound(_, _) =>
          Set.empty[EntityId]
        case GetFailure(_, _) =>
          throw new RuntimeException(
            s"Unable to get an initial state within 'waiting-for-state-timeout': ${PrettyDuration.format(askTimeout.duration)}")
        case GetDataDeleted(_, _) =>
          throw new RuntimeException(s"Unable to get an initial state because it was deleted")

      }
    }
    Future.sequence(responsePerKey).map(_.flatten)(ExecutionContexts.parasitic)
  }

}
