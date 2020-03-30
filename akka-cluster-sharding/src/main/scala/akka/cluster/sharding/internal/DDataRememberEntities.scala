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

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class DDataRememberEntitiesShardStoreProvider(
    system: ActorSystem,
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends RememberEntitiesShardStoreProvider {

  implicit private val node = Cluster(system)
  implicit private val selfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  override def createStoreForShard(shardId: ShardId): RememberEntitiesShardStore =
    new DDataRememberEntities(shardId, system, typeName, settings, replicator, majorityMinCap)

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object DDataRememberEntities {
  val FutureDone: Future[Done] = Future.successful(Done)
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class DDataRememberEntities(
    shardId: ShardId,
    system: ActorSystem,
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)(implicit val node: Cluster, selfUniqueAddress: SelfUniqueAddress)
    extends RememberEntitiesShardStore {

  import DDataRememberEntities._
  import system.dispatcher

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
  // Note that the timeout is actually updatingStateTimeout x 3 since we do 3 retries
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)
  private val maxUpdateAttempts = 3

  private def key(shardId: ShardId, entityId: EntityId): ORSetKey[EntityId] = {
    val i = math.abs(entityId.hashCode % numberOfKeys)
    stateKeys(shardId)(i)
  }

  override def addEntity(entityId: EntityId): Future[Done] = {
    implicit val askTimeout = Timeout(writeMajority.timeout * 2)
    def tryAddEntity(retriesLeft: Int): Future[Done] = {
      val result = (replicator ? Update(key(shardId, entityId), ORSet.empty[EntityId], writeMajority) { existing =>
        existing :+ entityId
      }).mapTo[UpdateResponse[ORSet[EntityId]]]

      result.flatMap(retryDoneOrFail(_, retriesLeft, () => tryAddEntity(retriesLeft - 1)))
    }

    tryAddEntity(maxUpdateAttempts)
  }

  override def removeEntity(entityId: EntityId): Future[Done] = {
    implicit val askTimeout = Timeout(writeMajority.timeout * 2)
    def tryRemoveEntity(retriesLeft: Int): Future[Done] = {
      val result = (replicator ? Update(key(shardId, entityId), ORSet.empty[EntityId], writeMajority) { existing =>
        existing.remove(entityId)
      }).mapTo[UpdateResponse[ORSet[EntityId]]]

      result.flatMap(retryDoneOrFail(_, maxUpdateAttempts, () => tryRemoveEntity(retriesLeft - 1)))
    }

    tryRemoveEntity(maxUpdateAttempts)
  }

  private def retryDoneOrFail(
      res: UpdateResponse[ORSet[EntityId]],
      retriesLeft: Int,
      retry: () => Future[Done]): Future[Done] = res match {
    case UpdateSuccess(_, _) =>
      FutureDone
    case UpdateTimeout(_, _) =>
      if (retriesLeft > 0) retry()
      else
        throw new RuntimeException(
          "Unable to update state, within " +
          s"'updating-state-timeout'= ${PrettyDuration.format(writeMajority.timeout)}, " +
          s"gave up after $maxUpdateAttempts retries")
    case StoreFailure(_, _) =>
      throw new RuntimeException("Unable to update state, due to store failure")
    case ModifyFailure(_, error, cause, _) =>
      throw new RuntimeException(s"Unable to update state, due to modify failure: $error", cause)
    case UpdateDataDeleted(_, _) =>
      throw new RuntimeException(s"Unable to update state, due to delete")
  }

  override def getEntities(): Future[Set[EntityId]] = {
    import system.dispatcher
    implicit val askTimeout = Timeout(readMajority.timeout * 2)
    val responsePerKey = (0 until numberOfKeys).toSet[Int].map { i =>
      val key = stateKeys(shardId)(i)
      (replicator ? Get(key, readMajority, Some(i))).mapTo[GetResponse[ORSet[ShardId]]].map {
        case success @ GetSuccess(_, _) =>
          success.dataValue.elements
        case NotFound(_, _) =>
          Set.empty[EntityId]
        case GetFailure(_, _) =>
          throw new RuntimeException(
            s"Unable to get an initial state within 'waiting-for-state-timeout': ${PrettyDuration.format(
              askTimeout.duration)} using ${readMajority}")
        case GetDataDeleted(_, _) =>
          throw new RuntimeException(s"Unable to get an initial state because it was deleted")
      }
    }
    Future.sequence(responsePerKey).map(_.flatten)(ExecutionContexts.parasitic)
  }

  override def stop(): Unit = ()

  override def toString: ShardId = s"DDataRememberEntities($typeName, $replicator)"
}
