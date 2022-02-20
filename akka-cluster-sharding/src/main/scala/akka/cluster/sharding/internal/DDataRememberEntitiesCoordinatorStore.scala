/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ddata.GSet
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.ShardId

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object DDataRememberEntitiesCoordinatorStore {
  def props(typeName: String, settings: ClusterShardingSettings, replicator: ActorRef, majorityMinCap: Int): Props =
    Props(new DDataRememberEntitiesCoordinatorStore(typeName, settings, replicator, majorityMinCap))
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class DDataRememberEntitiesCoordinatorStore(
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Actor
    with ActorLogging {

  implicit val node: Cluster = Cluster(context.system)
  implicit val selfUniqueAddress: SelfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)

  private val AllShardsKey = GSetKey[String](s"shard-${typeName}-all")
  private var allShards: Option[Set[ShardId]] = None
  private var coordinatorWaitingForShards: Option[ActorRef] = None

  // eager load of remembered shard ids
  def getAllShards(): Unit = {
    replicator ! Replicator.Get(AllShardsKey, readMajority)
  }
  getAllShards()

  override def receive: Receive = {
    case RememberEntitiesCoordinatorStore.GetShards =>
      allShards match {
        case Some(shardIds) =>
          coordinatorWaitingForShards = Some(sender())
          onGotAllShards(shardIds);
        case None =>
          // reply when we get them, since there is only ever one coordinator communicating with us
          // and it may retry we can just keep the latest sender
          coordinatorWaitingForShards = Some(sender())
      }

    case g @ Replicator.GetSuccess(AllShardsKey, _) =>
      onGotAllShards(g.get(AllShardsKey).elements)

    case Replicator.NotFound(AllShardsKey, _) =>
      onGotAllShards(Set.empty)

    case Replicator.GetFailure(AllShardsKey, _) =>
      log.error(
        "The ShardCoordinator was unable to get all shards state within 'waiting-for-state-timeout': {} millis (retrying)",
        readMajority.timeout.toMillis)
      // repeat until GetSuccess
      getAllShards()

    case RememberEntitiesCoordinatorStore.AddShard(shardId) =>
      replicator ! Replicator.Update(AllShardsKey, GSet.empty[String], writeMajority, Some((sender(), shardId)))(
        _ + shardId)

    case Replicator.UpdateSuccess(AllShardsKey, Some((replyTo: ActorRef, shardId: ShardId))) =>
      log.debug("The coordinator shards state was successfully updated with {}", shardId)
      replyTo ! RememberEntitiesCoordinatorStore.UpdateDone(shardId)

    case Replicator.UpdateTimeout(AllShardsKey, Some((replyTo: ActorRef, shardId: ShardId))) =>
      log.error(
        "The ShardCoordinator was unable to update shards distributed state within 'updating-state-timeout': {} millis (retrying), adding shard={}",
        writeMajority.timeout.toMillis,
        shardId)
      replyTo ! RememberEntitiesCoordinatorStore.UpdateFailed(shardId)

    case Replicator.ModifyFailure(key, error, cause, Some((replyTo: ActorRef, shardId: ShardId))) =>
      log.error(
        cause,
        "The remember entities store was unable to add shard [{}] (key [{}], failed with error: {})",
        shardId,
        key,
        error)
      replyTo ! RememberEntitiesCoordinatorStore.UpdateFailed(shardId)
  }

  def onGotAllShards(shardIds: Set[ShardId]): Unit = {
    coordinatorWaitingForShards match {
      case Some(coordinator) =>
        coordinator ! RememberEntitiesCoordinatorStore.RememberedShards(shardIds)
        coordinatorWaitingForShards = None
        // clear the shards out now that we have sent them to coordinator, to save some memory
        allShards = None
      case None =>
        // wait for coordinator to ask
        allShards = Some(shardIds)
    }
  }

}
