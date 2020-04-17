/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Stash
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

private[akka] object DDataRememberEntitiesCoordinatorStore {
  def props(typeName: String, settings: ClusterShardingSettings, replicator: ActorRef, majorityMinCap: Int): Props =
    Props(new DDataRememberEntitiesCoordinatorStore(typeName, settings, replicator, majorityMinCap))
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class DDataRememberEntitiesCoordinatorStore(
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Actor
    with Stash
    with ActorLogging {

  implicit val node: Cluster = Cluster(context.system)
  implicit val selfUniqueAddress: SelfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)

  private val AllShardsKey = GSetKey[String](s"shard-${typeName}-all")
  private var allShards = Set.empty[ShardId]

  // eager load of remembered shard ids
  def getAllShards(): Unit = {
    replicator ! Replicator.Get(AllShardsKey, readMajority)
  }
  getAllShards()

  override def receive: Receive = waitingForRead()

  def waitingForRead(): Receive = {

    case RememberEntitiesCoordinatorStore.GetShards =>
      stash()

    case g @ Replicator.GetSuccess(AllShardsKey, _) =>
      allShards = g.get(AllShardsKey).elements
      context.become(idle())
      unstashAll()

    case Replicator.GetFailure(AllShardsKey, _) =>
      log.error(
        "The ShardCoordinator was unable to get all shards state within 'waiting-for-state-timeout': {} millis (retrying)",
        readMajority.timeout.toMillis)
      // repeat until GetSuccess
      getAllShards()

    case Replicator.NotFound(AllShardsKey, _) =>
      allShards = Set.empty
      context.become(idle())
      unstashAll()
  }

  def idle(): Receive = {
    case RememberEntitiesCoordinatorStore.GetShards =>
      // FIXME should we clear the set once we have replied given that we know there is only one get-request ever?
      sender ! RememberEntitiesCoordinatorStore.RememberedShards(allShards)
    case RememberEntitiesCoordinatorStore.AddShard(shardId) =>
      if (!allShards.contains(shardId)) {
        addShard(shardId, sender())
      } else {
        // already known started shard
        sender() ! RememberEntitiesCoordinatorStore.UpdateDone(shardId)
      }
  }

  def addShard(newShard: ShardId, replyTo: ActorRef): Unit = {
    replicator ! Replicator.Update(AllShardsKey, GSet.empty[String], writeMajority, Some(newShard))(_ + newShard)
    context.become(waitingForWrite(replyTo))
  }

  def waitingForWrite(replyTo: ActorRef): Receive = {
    case Replicator.UpdateSuccess(AllShardsKey, Some(newShard: String)) =>
      log.debug("The coordinator shards state was successfully updated with {}", newShard)
      replyTo ! RememberEntitiesCoordinatorStore.UpdateDone(newShard)
      context.become(idle())

    case Replicator.UpdateTimeout(AllShardsKey, Some(newShard: String)) =>
      log.error(
        "The ShardCoordinator was unable to update shards distributed state within 'updating-state-timeout': {} millis (retrying), adding shard={}",
        writeMajority.timeout.toMillis,
        newShard)
      replyTo ! RememberEntitiesCoordinatorStore.UpdateFailed(newShard)
      context.become(idle())

    case Replicator.ModifyFailure(key, error, cause, Some(newShard: String)) =>
      log.error(
        cause,
        "The remember entities store was unable to add shard [{}] (key [{}], failed with error: {})",
        newShard,
        key,
        error)
      replyTo ! RememberEntitiesCoordinatorStore.UpdateFailed(newShard)
      context.become(idle())
  }
}
