/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.LocalActorRef
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.actor.Stash
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Changed
import akka.cluster.ddata.Replicator.Subscribe
import akka.cluster.sharding.ShardCoordinator
import akka.cluster.sharding.ShardRegion.ShardId
import akka.event.Logging
import akka.util.Timeout

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._

// TODO reduce logging
object DynamicShardAllocationStrategy {

  type ShardRegion = ActorRef

  // local only messages
  private final case class GetShardLocation(shard: ShardId) extends NoSerializationVerificationNeeded
  private final case object GetShardLocations extends NoSerializationVerificationNeeded

  private final case class GetShardLocationsResponse(desiredAllocations: Map[ShardId, ShardLocation])
      extends NoSerializationVerificationNeeded
  private final case class GetShardLocationResponse(address: Option[Address]) extends NoSerializationVerificationNeeded

  // TODO serializer, datatype used with ddata, use a normal class to evolve
  final case class ShardLocation(address: Address)

  private object DDataStateActor {
    def props(typeName: String) = Props(new DDataStateActor(typeName))
  }

  private class DDataStateActor(typeName: String) extends Actor with ActorLogging with Stash {
    private val DataKey = LWWMapKey[ShardId, ShardLocation](s"dynamic-sharding-$typeName")
    // it's own replicator or configurable?
    private val replicator = DistributedData(context.system).replicator

    override def preStart(): Unit = {
      log.info("Starting ddata state actor for {}", typeName)
      replicator ! Subscribe(DataKey, self)
    }

    var currentLocations: Map[ShardId, ShardLocation] = Map.empty

    override def receive: Receive = active

    private def active: Receive = {
      case c @ Changed(`DataKey`) =>
        currentLocations = c.get(DataKey).entries
        log.info("Received updated shard locations {}", currentLocations)
      case GetShardLocation(shard) =>
        log.info("GetShardLocation {}", shard)
        val shardLocation = currentLocations.get(shard).map(_.address)
        sender() ! GetShardLocationResponse(shardLocation)
      case GetShardLocations =>
        log.info("GetShardLocations")
        sender() ! GetShardLocationsResponse(currentLocations)
    }
  }
}

class DynamicShardAllocationStrategy(system: ActorSystem, typeName: String, allowNonLocalAllocations: Boolean = false)
    extends ShardCoordinator.ShardAllocationStrategy {

  import DynamicShardAllocationStrategy._
  import akka.pattern.ask
  import system.dispatcher

  // local only ask
  private implicit val timeout = Timeout(10.seconds)

  private val log = Logging(system, classOf[DynamicShardAllocationStrategy])

  private val shardState = system
    .asInstanceOf[ExtendedActorSystem]
    .systemActorOf(DDataStateActor.props(typeName), s"dynamic-allocation-state-$typeName")

  private val cluster = Cluster(system)

  override def allocateShard(
      requester: ShardRegion,
      shardId: ShardId,
      currentShardAllocations: Map[ShardRegion, immutable.IndexedSeq[ShardId]]): Future[ShardRegion] = {

    // TODO debug or remove
    log.debug("allocateShard {} {} {}", shardId, requester, currentShardAllocations)

    // current shard allocations include all current shard regions
    val allocation: Future[ShardRegion] = (shardState ? GetShardLocation(shardId)).mapTo[GetShardLocationResponse].map {
      case GetShardLocationResponse(None) =>
        log.debug("No specific location for shard {}. Allocating to requester {}", shardId, requester)
        requester
      case GetShardLocationResponse(Some(address)) =>
        // if it is the local address, convert it so it is found in the shards
        if (address == cluster.selfAddress) {
          currentShardAllocations.keys.find(_.path.address.hasLocalScope) match {
            case None =>
              log.warning("unable to find local shard in currentShardAllocation. Using requester")
              requester
            case Some(localShardRegion) =>
              log.debug("allocating to local shard")
              localShardRegion
          }
        } else {
          currentShardAllocations.keys.find(_.path.address == address) match {
            case None =>
              log.warning(
                "Dynamic shard location [{}] for shard {} not found in members [{}]",
                address,
                shardId,
                currentShardAllocations.keys.mkString(","))
              requester
            case Some(location) =>
              log.debug("Moving shard to location {}", location)
              if (!allowNonLocalAllocations && location.path.address != requester.path.address) {
                // sanity check during initial testing
//                throw new RuntimeException(
//                  s"Tried to allocate shard ${shardId} to ${location} but request coming from ${requester}")
              }

              location
          }
        }

    }

    // TODO map the AskException even tho it is local
    allocation
  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {

    log.debug("rebalance {} {}", currentShardAllocations, rebalanceInProgress)

    // TODO should we deal with failure?
    val currentAllocationByAddress: Map[Address, immutable.IndexedSeq[ShardId]] = currentShardAllocations.map {
      case (_: LocalActorRef, value) =>
        (Cluster(system).selfAddress, value) // so it can be compared to a address with host and port
      case (key, value) => (key.path.address, value)
    }

    log.debug("Current allocations by address: " + currentAllocationByAddress)

    // this will currently return shards that are not allocated. This could be checked by looking in
    // all the currentShardAllocations but probably not worth it as shard coordinator will ignore
    // rebalance requests for shards it hasn't seen yet
    val shardsThatNeedRebalanced: Future[Set[ShardId]] = for {
      desiredMappings <- (shardState ? GetShardLocations).mapTo[GetShardLocationsResponse]
    } yield {
      log.debug("desired allocations: {}", desiredMappings.desiredAllocations)
      desiredMappings.desiredAllocations.filterNot {
        case (shardId, expectedLocation) =>
          currentAllocationByAddress.get(expectedLocation.address) match {
            case None =>
              log.debug(
                "Shard {} desired location {} is not part of the cluster, not rebalancing",
                shardId,
                expectedLocation)
              true // not a current allocation so don't rebalance yet
            case Some(shards) =>
              val inCorrectLocation = shards.contains(shardId)
              inCorrectLocation
          }
      }
    }.keys.toSet

    // TODO debug or remove
    shardsThatNeedRebalanced.map { done =>
      log.debug("Shards not currently in their desired location {}", done)
      done
    }
  }
}
