/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.internal.coordinator

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.cluster.Cluster
import akka.cluster.ddata.GSet
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetFailure
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.ShardId

import scala.concurrent.Future

private[akka] class DDataCoordinatorRememberEntitiesStore(
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Actor
    with ActorLogging {

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)

  private implicit val node: Cluster = Cluster(context.system)
  private implicit val selfUniqueAddress: SelfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  private val AllShardsKey = GSetKey[ShardId](s"shard-${typeName}-all")

  // FIXME eagerly load the set of shards on startup?

  override def receive: Receive = idle()

  private def idle(): Receive = {
    case CoordinatorRememberEntitiesStore.GetShards         => onGetShards(sender())
    case CoordinatorRememberEntitiesStore.AddShard(shardId) => onAddShard(shardId, sender())
  }

  private def onGetShards(replyTo: ActorRef): Unit = {
    require(settings.rememberEntities)
    replicator ! Get(AllShardsKey, readMajority)

    context.become({
      case g @ GetSuccess(AllShardsKey, _) =>
        replyTo ! CoordinatorRememberEntitiesStore.InitialShards(g.get(AllShardsKey).elements)
        context.become(idle())

      case GetFailure(AllShardsKey, _) =>
        log.error(
          "Unable to get all shards state within 'waiting-for-state-timeout': {} millis (retrying)",
          readMajority.timeout.toMillis)
        // repeat until GetSuccess
        onGetShards(replyTo)

      case NotFound(AllShardsKey, _) =>
        // remember entities cold start
        Future.successful(Set.empty)
    })
    replyTo ! CoordinatorRememberEntitiesStore.InitialShards(Set.empty)
  }

  private def onAddShard(shardId: ShardId, replyTo: ActorRef): Unit = {
    val addShard = () =>
      replicator ! Update(AllShardsKey, GSet.empty[String], writeMajority, Some(shardId))(_ + shardId)

    addShard()
    context.become(waitingForShardUpdate(shardId, replyTo, addShard))
  }

  private def waitingForShardUpdate(shardId: ShardId, replyTo: ActorRef, retry: () => Unit): Receive = {
    case UpdateSuccess(AllShardsKey, Some(newShard: String)) =>
      log.debug("The coordinator shards state was successfully updated with {}", newShard)
      replyTo ! CoordinatorRememberEntitiesStore.ShardIdAdded(shardId)
      context.become(idle())

    case UpdateTimeout(AllShardsKey, Some(newShard: String)) =>
      log.error(
        "Unable to update remember entities shard distributed state within 'updating-state-timeout': {} millis",
        writeMajority.timeout.toMillis)
      retry()
  }
}
