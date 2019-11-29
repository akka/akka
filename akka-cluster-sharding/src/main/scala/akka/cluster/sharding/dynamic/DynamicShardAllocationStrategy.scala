/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.AddressFromURIString
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
import akka.pattern.AskTimeoutException
import akka.util.Timeout

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._

object DynamicShardAllocationStrategy {

  type ShardRegion = ActorRef

  // local only messages
  private[akka] final case class GetShardLocation(shard: ShardId) extends NoSerializationVerificationNeeded
  private[akka] final case object GetShardLocations extends NoSerializationVerificationNeeded

  private[akka] final case class GetShardLocationsResponse(desiredAllocations: Map[ShardId, Address])
      extends NoSerializationVerificationNeeded
  private[akka] final case class GetShardLocationResponse(address: Option[Address])
      extends NoSerializationVerificationNeeded

  // only returned locally, serialized as a string
  final case class ShardLocation(address: Address) extends NoSerializationVerificationNeeded

  private object DDataStateActor {
    def props(typeName: String) = Props(new DDataStateActor(typeName))
  }

  private class DDataStateActor(typeName: String) extends Actor with ActorLogging with Stash {

    private val ddataKeys =
      context.system.settings.config.getInt("akka.cluster.sharding.dynamic-shard-allocation-strategy.ddata-keys")

    // uses a string primitive types are optimized in ddata to not serialize every entity
    // separately
    private val DataKeys = (0 until ddataKeys).map(i => LWWMapKey[ShardId, String](s"dynamic-sharding-$typeName-$i"))
    // it's own replicator or configurable?
    private val replicator = DistributedData(context.system).replicator

    override def preStart(): Unit = {
      log.debug("Starting ddata state actor for {}", typeName)
      DataKeys.foreach { key =>
        replicator ! Subscribe(key, self)
      }
    }

    var currentLocations: Map[ShardId, String] = Map.empty

    override def receive: Receive = active

    private def active: Receive = {
      case c @ Changed(key: LWWMapKey[ShardId, String] @unchecked) =>
        val newLocations = c.get(key).entries
        currentLocations ++= newLocations
        log.debug("Received updated shard locations {} all locations are now {}", newLocations, currentLocations)
      case GetShardLocation(shard) =>
        log.debug("GetShardLocation {}", shard)
        val shardLocation = currentLocations.get(shard).map(asStr => AddressFromURIString(asStr))
        sender() ! GetShardLocationResponse(shardLocation)
      case GetShardLocations =>
        log.debug("GetShardLocations")
        sender() ! GetShardLocationsResponse(currentLocations.mapValues(asStr => AddressFromURIString(asStr)))
    }
  }
}

class DynamicShardAllocationStrategy(system: ActorSystem, typeName: String)(
    // local only ask
    implicit val timeout: Timeout = Timeout(5.seconds))
    extends ShardCoordinator.ShardAllocationStrategy {

  import DynamicShardAllocationStrategy._
  import akka.pattern.ask
  import system.dispatcher

  private val log = Logging(system, classOf[DynamicShardAllocationStrategy])

  private val shardState = createShardStateActor()

  private[akka] def createShardStateActor(): ActorRef = {
    system
      .asInstanceOf[ExtendedActorSystem]
      .systemActorOf(DDataStateActor.props(typeName), s"dynamic-allocation-state-$typeName")
  }

  private val cluster = Cluster(system)

  override def allocateShard(
      requester: ShardRegion,
      shardId: ShardId,
      currentShardAllocations: Map[ShardRegion, immutable.IndexedSeq[ShardId]]): Future[ShardRegion] = {

    log.debug("allocateShard {} {} {}", shardId, requester, currentShardAllocations)

    // current shard allocations include all current shard regions
    (shardState ? GetShardLocation(shardId))
      .mapTo[GetShardLocationResponse]
      .map {
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
                log.debug("Allocating shard to location {}", location)
                location
            }
          }
      }
      .recover {
        case _: AskTimeoutException =>
          log.warning("allocate timed out waiting for shard allocation state")
          requester
      }

  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {

    log.debug("rebalance {} {}", currentShardAllocations, rebalanceInProgress)

    val currentAllocationByAddress: Map[Address, immutable.IndexedSeq[ShardId]] = currentShardAllocations.map {
      case (_: LocalActorRef, value) =>
        (Cluster(system).selfAddress, value) // so it can be compared to a address with host and port
      case (key, value) => (key.path.address, value)
    }

    log.debug("Current allocations by address: " + currentAllocationByAddress)

    val shardsThatNeedRebalanced: Future[Set[ShardId]] = for {
      desiredMappings <- (shardState ? GetShardLocations).mapTo[GetShardLocationsResponse]
    } yield {
      log.debug("desired allocations: {}", desiredMappings.desiredAllocations)
      desiredMappings.desiredAllocations.filterNot {
        case (shardId, expectedLocation) =>
          currentAllocationByAddress.get(expectedLocation) match {
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

    shardsThatNeedRebalanced
      .map { done =>
        log.debug("Shards not currently in their desired location {}", done)
        done
      }
      .recover {
        case _: AskTimeoutException =>
          log.warning("rebalance timed out waiting for shard allocation state")
          Set.empty
      }
  }
}
