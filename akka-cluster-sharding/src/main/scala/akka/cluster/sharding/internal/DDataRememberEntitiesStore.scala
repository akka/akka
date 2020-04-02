/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetDataDeleted
import akka.cluster.ddata.Replicator.GetFailure
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.ModifyFailure
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.StoreFailure
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateDataDeleted
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId
import akka.util.PrettyDuration._

import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class DDataRememberEntitiesShardStoreProvider(
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends RememberEntitiesShardStoreProvider {

  override def shardStoreProps(shardId: ShardId): Props =
    Props(new DDataRememberEntitiesStore(shardId, typeName, settings, replicator, majorityMinCap))

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object DDataRememberEntitiesStore {

  def props(
      shardId: ShardId,
      typeName: String,
      settings: ClusterShardingSettings,
      replicator: ActorRef,
      majorityMinCap: Int): Props =
    Props(new DDataRememberEntitiesStore(shardId, typeName, settings, replicator, majorityMinCap))

  // The default maximum-frame-size is 256 KiB with Artery.
  // When using entity identifiers with 36 character strings (e.g. UUID.randomUUID).
  // By splitting the elements over 5 keys we can support 10000 entities per shard.
  // The Gossip message size of 5 ORSet with 2000 ids is around 200 KiB.
  // This is by intention not configurable because it's important to have the same
  // configuration on each node.
  private val numberOfKeys = 5

  private def stateKeys(typeName: String, shardId: ShardId): Array[ORSetKey[EntityId]] =
    Array.tabulate(numberOfKeys)(i => ORSetKey[EntityId](s"shard-$typeName-$shardId-$i"))

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class DDataRememberEntitiesStore(
    shardId: ShardId,
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Actor
    with ActorLogging {

  import DDataRememberEntitiesStore._

  implicit val ec: ExecutionContext = context.dispatcher
  implicit val node: Cluster = Cluster(context.system)
  implicit val selfUniqueAddress: SelfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  // Note that the timeout is actually updatingStateTimeout x 3 since we do 3 retries
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)
  private val maxUpdateAttempts = 3
  private val keys = stateKeys(typeName, shardId)

  log.debug("Starting up DDataRememberEntitiesStore")
  // FIXME potential optimization: start loading entity ids immediately on start instead of waiting for request
  // (then throw away after request has been seen)

  private def key(entityId: EntityId): ORSetKey[EntityId] = {
    val i = math.abs(entityId.hashCode % numberOfKeys)
    keys(i)
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case RememberEntitiesShardStore.AddEntity(entityId)    => onAddEntity(entityId)
    case RememberEntitiesShardStore.RemoveEntity(entityId) => onRemovEntity(entityId)
    case RememberEntitiesShardStore.GetEntities            => onGetEntities()
  }

  def waitingForAllEntityIds(requestor: ActorRef, gotKeys: Set[Int], ids: Set[EntityId]): Receive = {
    def receiveOne(i: Int, idsForKey: Set[EntityId]): Unit = {
      val newGotKeys = gotKeys + i
      val newIds = ids.union(idsForKey)
      if (newGotKeys.size == numberOfKeys) {
        requestor ! RememberEntitiesShardStore.RememberedEntities(newIds)
        context.become(idle)
      } else {
        context.become(waitingForAllEntityIds(requestor, newGotKeys, newIds))
      }
    }

    {
      case g @ GetSuccess(_, Some(i: Int)) =>
        val key = keys(i)
        val ids = g.get(key).elements
        receiveOne(i, ids)
      case NotFound(_, Some(i: Int)) =>
        receiveOne(i, Set.empty)
      case GetFailure(key, _) =>
        throw new RuntimeException(
          s"Unable to get an initial state within 'waiting-for-state-timeout': ${readMajority.timeout.pretty} using ${readMajority} (key $key)")
      case GetDataDeleted(_, _) =>
        throw new RuntimeException(s"Unable to get an initial state because it was deleted")
    }
  }

  private def onAddEntity(entityId: EntityId): Unit = {
    val sendUpdate = () =>
      replicator ! Update(key(entityId), ORSet.empty[EntityId], writeMajority) { existing =>
        existing :+ entityId
      }

    sendUpdate()
    context.become(waitingForUpdate(sender(), entityId, maxUpdateAttempts, retry = sendUpdate))
  }

  private def onRemovEntity(entityId: EntityId): Unit = {
    val sendUpdate = () =>
      replicator ! Update(key(entityId), ORSet.empty[EntityId], writeMajority) { existing =>
        existing.remove(entityId)
      }

    sendUpdate()
    context.become(waitingForUpdate(sender(), entityId, maxUpdateAttempts, retry = sendUpdate))
  }

  private def waitingForUpdate(requestor: ActorRef, entityId: EntityId, retriesLeft: Int, retry: () => Unit): Receive = {
    case UpdateSuccess(_, _) =>
      log.debug("The DDataShard state was successfully updated for [{}]", entityId)
      requestor ! RememberEntitiesShardStore.UpdateDone(entityId)

    case UpdateTimeout(_, _) =>
      if (retriesLeft > 0) retry()
      else
        throw new RuntimeException(
          "Unable to update state, within " +
          s"'updating-state-timeout'= ${writeMajority.timeout.pretty}, " +
          s"gave up after $maxUpdateAttempts retries")
    case StoreFailure(_, _) =>
      throw new RuntimeException("Unable to update state, due to store failure")
    case ModifyFailure(_, error, cause, _) =>
      throw new RuntimeException(s"Unable to update state, due to modify failure: $error", cause)
    case UpdateDataDeleted(_, _) =>
      throw new RuntimeException(s"Unable to update state, due to delete")
  }

  private def onGetEntities(): Unit = {
    (0 until numberOfKeys).toSet[Int].foreach { i =>
      val key = keys(i)
      replicator ! Get(key, readMajority, Some(i))
    }
    context.become(waitingForAllEntityIds(sender(), Set.empty, Set.empty))
  }

}
