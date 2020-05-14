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
import akka.cluster.sharding.internal.RememberEntitiesShardStore.{ AddEntities, RemoveEntity }
import akka.util.PrettyDuration._

import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object DDataRememberEntitiesShardStore {

  def props(
      shardId: ShardId,
      typeName: String,
      settings: ClusterShardingSettings,
      replicator: ActorRef,
      majorityMinCap: Int): Props =
    Props(new DDataRememberEntitiesShardStore(shardId, typeName, settings, replicator, majorityMinCap))

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
private[akka] final class DDataRememberEntitiesShardStore(
    shardId: ShardId,
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Actor
    with ActorLogging {

  import DDataRememberEntitiesShardStore._

  implicit val ec: ExecutionContext = context.dispatcher
  implicit val node: Cluster = Cluster(context.system)
  implicit val selfUniqueAddress: SelfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  // Note that the timeout is actually updatingStateTimeout x 3 since we do 3 retries
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)
  private val maxUpdateAttempts = 3
  private val keys = stateKeys(typeName, shardId)

  if (log.isDebugEnabled) {
    log.debug(
      "Starting up DDataRememberEntitiesStore, write timeout: [{}], read timeout: [{}], majority min cap: [{}]",
      settings.tuningParameters.waitingForStateTimeout.pretty,
      settings.tuningParameters.updatingStateTimeout.pretty,
      majorityMinCap)
  }
  // FIXME potential optimization: start loading entity ids immediately on start instead of waiting for request
  // (then throw away after request has been seen)

  private def key(entityId: EntityId): ORSetKey[EntityId] = {
    val i = math.abs(entityId.hashCode % numberOfKeys)
    keys(i)
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case RememberEntitiesShardStore.GetEntities => onGetEntities()
    case AddEntities(ids)                       => addEntities(ids)
    case RemoveEntity(id)                       => removeEntity(id)
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
        log.error(
          "Unable to get an initial state within 'waiting-for-state-timeout': [{}] using [{}] (key [{}])",
          readMajority.timeout.pretty,
          readMajority,
          key)
        context.stop(self)
      case GetDataDeleted(_, _) =>
        log.error("Unable to get an initial state because it was deleted")
        context.stop(self)
    }
  }

  private def addEntities(ids: Set[EntityId]): Unit = {
    val updates: Map[Set[EntityId], (Update[ORSet[EntityId]], Int)] = ids.groupBy(key).map {
      case (key, ids) =>
        (ids, (Update(key, ORSet.empty[EntityId], writeMajority, Some(ids)) { existing =>
          ids.foldLeft(existing) {
            case (acc, nextId) => acc :+ nextId
          }
        }, maxUpdateAttempts))
    }

    updates.foreach {
      case (_, (update, _)) =>
        replicator ! update
    }

    context.become(waitingForUpdates(sender(), ids, updates))
  }

  private def removeEntity(id: EntityId): Unit = {
    val keyForEntity = key(id)
    val update = Update(keyForEntity, ORSet.empty[EntityId], writeMajority, Some(Set(id))) { existing =>
      existing.remove(id)
    }
    replicator ! update

    context.become(waitingForUpdates(sender(), Set(id), Map((Set(id), (update, maxUpdateAttempts)))))
  }

  private def waitingForUpdates(
      requestor: ActorRef,
      allIds: Set[EntityId],
      updates: Map[Set[EntityId], (Update[ORSet[EntityId]], Int)]): Receive = {
    case UpdateSuccess(_, Some(ids: Set[EntityId] @unchecked)) =>
      log.debug("The DDataShard state was successfully updated for [{}]", ids)
      val remaining = updates - ids
      if (remaining.isEmpty) {
        requestor ! RememberEntitiesShardStore.UpdateDone(allIds)
        context.become(idle)
      } else {
        context.become(waitingForUpdates(requestor, allIds, remaining))
      }

    case UpdateTimeout(_, Some(ids: Set[EntityId] @unchecked)) =>
      val (update, retriesLeft) = updates(ids)
      if (retriesLeft > 0) {
        log.debug("Retrying update because of write timeout, tries left [{}]", retriesLeft)
        replicator ! update
        context.become(waitingForUpdates(requestor, allIds, updates.updated(ids, (update, retriesLeft - 1))))
      } else {
        log.error(
          "Unable to update state, within 'updating-state-timeout'= [{}], gave up after [{}] retries",
          writeMajority.timeout.pretty,
          maxUpdateAttempts)
        // will trigger shard restart
        context.stop(self)
      }
    case StoreFailure(_, _) =>
      log.error("Unable to update state, due to store failure")
      // will trigger shard restart
      context.stop(self)
    case ModifyFailure(_, error, cause, _) =>
      log.error(cause, "Unable to update state, due to modify failure: {}", error)
      // will trigger shard restart
      context.stop(self)
    case UpdateDataDeleted(_, _) =>
      log.error("Unable to update state, due to delete")
      // will trigger shard restart
      context.stop(self)
  }

  private def onGetEntities(): Unit = {
    (0 until numberOfKeys).toSet[Int].foreach { i =>
      val key = keys(i)
      replicator ! Get(key, readMajority, Some(i))
    }
    context.become(waitingForAllEntityIds(sender(), Set.empty, Set.empty))
  }

}
