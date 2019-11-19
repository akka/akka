/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic.internal

import java.util.concurrent.CompletionStage

import akka.Done
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategy.ShardLocation
import akka.event.Logging
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

private[dynamic] class DynamicShardAllocationClientImpl(system: ActorSystem, typeName: String)
    extends akka.cluster.sharding.dynamic.scaladsl.DynamicShardAllocationClient
    with akka.cluster.sharding.dynamic.javadsl.DynamicShardAllocationClient {

  private val log = Logging(system, classOf[DynamicShardAllocationClientImpl])

  private val replicator: ActorRef = DistributedData(system).replicator
  private val self: SelfUniqueAddress = DistributedData(system).selfUniqueAddress
  private val DataKey: LWWMapKey[ShardId, ShardLocation] =
    LWWMapKey[ShardId, ShardLocation](s"dynamic-sharding-$typeName")
  private val timeout = 5.seconds
  private implicit val askTimeout = Timeout(timeout * 2)
  private implicit val ec = system.dispatcher

  // TODO configurable consistency, timeout etc

  override def updateShardLocation(shard: ShardId, location: Address): Future[Done] = {
    // TODO debug or remove
    log.info("updateShardLocation {} {}", shard, location)
    import akka.pattern.ask
    (replicator ? Update(DataKey, WriteMajority(timeout), None) {
      case None =>
        LWWMap.empty.put(self, shard, ShardLocation(location))
      case Some(existing) =>
        existing.put(self, shard, ShardLocation(address = location))
    }).flatMap {
      case UpdateSuccess(_, _) => Future.successful(Done)
      case UpdateTimeout       => Future.failed(new RuntimeException("oh noes"))
    }
  }

  // TODO API for getting the current shard allocations so can be exposed via akka management

  override def setShardLocation(shard: ShardId, location: Address): CompletionStage[Done] = {
    import scala.compat.java8.FutureConverters._
    updateShardLocation(shard, location).toJava
  }
}
