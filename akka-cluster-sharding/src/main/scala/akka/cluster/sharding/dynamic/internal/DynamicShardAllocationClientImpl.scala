/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic.internal

import java.util.concurrent.CompletionStage

import akka.Done
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.AddressFromURIString
import akka.annotation.InternalApi
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetFailure
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.ddata.Replicator.WriteLocal
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.dynamic.ClientTimeoutException
import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategy.ShardLocation
import akka.cluster.sharding.dynamic.ShardLocations
import akka.event.Logging
import akka.util.Timeout
import akka.util.PrettyDuration._
import akka.pattern.ask

import scala.concurrent.Future
import scala.compat.java8.FutureConverters._
import akka.util.JavaDurationConverters._
import com.github.ghik.silencer.silent

/**
 * INTERNAL API
 */
@InternalApi
final private[dynamic] class DynamicShardAllocationClientImpl(system: ActorSystem, typeName: String)
    extends akka.cluster.sharding.dynamic.scaladsl.DynamicShardAllocationClient
    with akka.cluster.sharding.dynamic.javadsl.DynamicShardAllocationClient {

  private val log = Logging(system, classOf[DynamicShardAllocationClientImpl])

  private val replicator: ActorRef = DistributedData(system).replicator
  private val self: SelfUniqueAddress = DistributedData(system).selfUniqueAddress
  private val ddataKeys =
    system.settings.config.getInt("akka.cluster.sharding.dynamic-shard-allocation-strategy.ddata-keys")

  private val DataKeys = (0 until ddataKeys).map(i => LWWMapKey[ShardId, String](s"dynamic-sharding-$typeName-$i"))

  private val timeout =
    system.settings.config.getDuration("akka.cluster.sharding.dynamic-shard-allocation-strategy.client-timeout").asScala
  private implicit val askTimeout = Timeout(timeout * 2)
  private implicit val ec = system.dispatchers.internalDispatcher

  override def updateShardLocation(shard: ShardId, location: Address): Future[Done] = {
    val key = DataKeys(math.abs(shard.hashCode() % ddataKeys))
    log.debug("updateShardLocation {} {} key {}", shard, location, key)
    (replicator ? Update(key, LWWMap.empty[ShardId, String], WriteLocal, None) { existing =>
      existing.put(self, shard, location.toString)
    }).flatMap {
      case UpdateSuccess(_, _) => Future.successful(Done)
      case UpdateTimeout =>
        Future.failed(new ClientTimeoutException(s"Unable to update shard location after ${timeout.duration.pretty}"))
    }
  }

  override def setShardLocation(shard: ShardId, location: Address): CompletionStage[Done] =
    updateShardLocation(shard, location).toJava

  @silent("deprecated") // mapValues dance
  override def shardLocations(): Future[ShardLocations] = {
    Future
      .traverse(DataKeys) { key =>
        (replicator ? Get(key, ReadMajority(timeout))).flatMap {
          case success @ GetSuccess(`key`, _) =>
            Future.successful(
              success.get(key).entries.mapValues(asStr => ShardLocation(AddressFromURIString(asStr))).toMap)
          case GetFailure(_, _) =>
            Future.failed(
              (new ClientTimeoutException(s"Unable to get shard locations after ${timeout.duration.pretty}")))
        }
      }
      .map { all =>
        new ShardLocations(all.reduce(_ ++ _).toMap)
      }

  }

  override def getShardLocations(): CompletionStage[ShardLocations] = shardLocations().toJava
}
