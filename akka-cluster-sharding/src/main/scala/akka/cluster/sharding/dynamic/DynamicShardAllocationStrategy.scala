/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.LocalActorRef
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Changed
import akka.cluster.ddata.Replicator.Subscribe
import akka.cluster.sharding.ShardCoordinator
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategy.ShardLocation
import akka.event.Logging
import akka.util.Timeout
import com.github.ghik.silencer.silent

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._

@silent
object DynamicShardAllocationStrategy {

  type ShardRegion = ActorRef
  sealed trait Command

  // local only messages
  case class GetShardLocation(shard: ShardId) extends NoSerializationVerificationNeeded
  case object GetShardLocations extends NoSerializationVerificationNeeded

  case class GetShardLocationsResponse(desiredAllocations: Map[ShardId, ShardLocation])
      extends NoSerializationVerificationNeeded
  case class GetShardLocationResponse(address: Option[Address]) extends NoSerializationVerificationNeeded

  // TODO serializer, datatype used with ddate
  case class ShardLocation(address: Address)

  object DDataStateActor {
    def props(typeName: String) = Props(new DDataStateActor(typeName))
  }

  class DDataStateActor(typeName: String) extends Actor with ActorLogging {

    private val DataKey = LWWMapKey[ShardId, ShardLocation](s"dynamic-sharding-$typeName")

    // it's own replicator or configurable?
    private val replicator = DistributedData(context.system).replicator

    override def preStart(): Unit = {
      // TODO only do this once we are the oldest member for the correct role
      replicator ! Subscribe(DataKey, self)
    }

    var currentLocations: Map[ShardId, ShardLocation] = Map.empty

    // TODO don't answer questions until the initial state is received
    override def receive: Receive = {
      case c @ Changed(`DataKey`) =>
        // new case
        // TODO changed case
        currentLocations = c.get(DataKey).entries
        log.info("Updated shard locations {}", currentLocations)
      case GetShardLocation(shard) =>
        val shardLocation = currentLocations.get(shard).map(_.address)
        sender() ! GetShardLocationResponse(shardLocation)
      case GetShardLocations =>
        sender() ! GetShardLocationsResponse(currentLocations)
    }
  }
}

// TODO make an extension and create max one per typeName
class DynamicShardAllocationStrategyClient(system: ActorSystem, typeName: String) {

  private val replicator: ActorRef = DistributedData(system).replicator
  private val self: SelfUniqueAddress = DistributedData(system).selfUniqueAddress
  private val DataKey: LWWMapKey[ShardId, ShardLocation] =
    LWWMapKey[ShardId, ShardLocation](s"dynamic-sharding-$typeName")
  private val timeout = 5.seconds
  private implicit val askTimeout = Timeout(timeout * 2)
  private implicit val ec = system.dispatcher

  // TODO configurable consistency, timeout etc

  def updateShardLocation(shard: ShardId, location: Address): Future[Done] = {
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

}

@silent
class DynamicShardAllocationStrategy(system: ActorSystem, typeName: String)
    extends ShardCoordinator.ShardAllocationStrategy {

  import DynamicShardAllocationStrategy._

  import akka.pattern.ask
  import system.dispatcher

  // local only ask
  private implicit val timeout = Timeout(10.seconds)

  private val log = Logging(system, classOf[DynamicShardAllocationStrategy])

  private val shardState = system.actorOf(Props(new DDataStateActor(typeName)))

  private val cluster = Cluster(system)

  // TODO is there a way go go from an actor ref to an UniqueAddress
  override def allocateShard(
      requester: ShardRegion,
      shardId: ShardId,
      currentShardAllocations: Map[ShardRegion, immutable.IndexedSeq[ShardId]]): Future[ShardRegion] = {

    // TODO debug or remove
    log.info("allocateShard {} {} {}", shardId, requester, currentShardAllocations)

    // current shard allocations include all current shard regions
    val allocation: Future[ShardRegion] = (shardState ? GetShardLocation(shardId)).mapTo[GetShardLocationResponse].map {
      case GetShardLocationResponse(None) =>
        log.info("No specific location for shard {}. Allocating to requestor {}", shardId, requester)
        requester
      case GetShardLocationResponse(Some(address)) =>
        // if it is the local address, convert it so it is found in the shards
        if (address == cluster.selfAddress) {
          currentShardAllocations.keys.find(_.path.address.hasLocalScope) match {
            case None =>
              log.warning("unable to find local shard in currentShardAllocation. Using requester")
              requester
            case Some(localShardRegion) =>
              log.info("allocating to local shard")
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
              log.info("Moving shard to dynamic location {}", location)
              location
          }
        }

    }

    allocation
  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {
    // return any shard that has moved

    log.info("rebalance {} {}", currentShardAllocations, rebalanceInProgress)

    // TODO should we deal with failure?
    // this doesn't work as local address isn't comparable to remote address
    val byAddress: Map[Address, immutable.IndexedSeq[ShardId]] = currentShardAllocations.map {
      case (_: LocalActorRef, value) => (Cluster(system).selfAddress, value)
      case (key, value)              => (key.path.address, value)
    }

    log.info("By address: " + byAddress)
    val shardsThatNeedRebalanced: Future[Set[ShardId]] = for {
      desiredMappings <- (shardState ? GetShardLocations).mapTo[GetShardLocationsResponse]
    } yield {
      println("desired allocations: " + desiredMappings.desiredAllocations)
      desiredMappings.desiredAllocations.filterNot {
        case (shardId, expectedLocation) =>
          byAddress.get(expectedLocation.address) match {
            case None =>
              true // not a current allocation so don't rebalance yet
            case Some(shards) =>
              shards.contains(shardId)
          }
      }
    }.keys.toSet

    // TODO debug or remove
    shardsThatNeedRebalanced.map { done =>
      println("Rebalancing shards " + done)
      done
    }
  }
}
