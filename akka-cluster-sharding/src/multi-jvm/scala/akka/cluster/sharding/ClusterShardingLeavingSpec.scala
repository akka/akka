/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor.{ Actor, ActorRef, Props }
import akka.serialization.jackson.CborSerializable
import akka.testkit._

object ClusterShardingLeavingSpec {
  case class Ping(id: String) extends CborSerializable

  class Entity extends Actor {
    def receive = {
      case Ping(_) => sender() ! self
    }
  }

  case object GetLocations extends CborSerializable
  case class Locations(locations: Map[String, ActorRef]) extends CborSerializable

  class ShardLocations extends Actor {
    var locations: Locations = _
    def receive = {
      case GetLocations => sender() ! locations
      case l: Locations => locations = l
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case m @ Ping(id) => (id, m)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Ping(id: String) => id.charAt(0).toString
  }
}

abstract class ClusterShardingLeavingSpecConfig(mode: String) extends MultiNodeClusterShardingConfig(mode) {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

}

object PersistentClusterShardingLeavingSpecConfig
    extends ClusterShardingLeavingSpecConfig(ClusterShardingSettings.StateStoreModePersistence)
object DDataClusterShardingLeavingSpecConfig
    extends ClusterShardingLeavingSpecConfig(ClusterShardingSettings.StateStoreModeDData)

class PersistentClusterShardingLeavingSpec
    extends ClusterShardingLeavingSpec(PersistentClusterShardingLeavingSpecConfig)
class DDataClusterShardingLeavingSpec extends ClusterShardingLeavingSpec(DDataClusterShardingLeavingSpecConfig)

class PersistentClusterShardingLeavingMultiJvmNode1 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode2 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode3 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode4 extends PersistentClusterShardingLeavingSpec

class DDataClusterShardingLeavingMultiJvmNode1 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode2 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode3 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode4 extends DDataClusterShardingLeavingSpec

abstract class ClusterShardingLeavingSpec(multiNodeConfig: ClusterShardingLeavingSpecConfig)
    extends MultiNodeClusterShardingSpec(multiNodeConfig)
    with ImplicitSender {
  import ClusterShardingLeavingSpec._
  import multiNodeConfig._

  def startSharding(): Unit = {
    startSharding(
      system,
      typeName = "Entity",
      entityProps = Props[Entity],
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  s"Cluster sharding ($mode) with leaving member" must {

    "join cluster" in within(20.seconds) {
      startPersistenceIfNotDdataMode(startOn = first, setStoreOn = roles)

      join(first, first, onJoinedRunOnFrom = startSharding())
      join(second, first, onJoinedRunOnFrom = startSharding())
      join(third, first, onJoinedRunOnFrom = startSharding())
      join(fourth, first, onJoinedRunOnFrom = startSharding())

      enterBarrier("after-2")
    }

    "initialize shards" in {
      runOn(first) {
        val shardLocations = system.actorOf(Props[ShardLocations], "shardLocations")
        val locations = (for (n <- 1 to 10) yield {
          val id = n.toString
          region ! Ping(id)
          id -> expectMsgType[ActorRef]
        }).toMap
        shardLocations ! Locations(locations)
      }
      enterBarrier("after-3")
    }

    "recover after leaving coordinator node" in {
      system.actorSelection(node(first) / "user" / "shardLocations") ! GetLocations
      val Locations(originalLocations) = expectMsgType[Locations]
      val firstAddress = node(first).address

      runOn(third) {
        cluster.leave(node(first).address)
      }

      runOn(first) {
        watch(region)
        expectTerminated(region, 15.seconds)
      }
      enterBarrier("stopped")

      runOn(second, third, fourth) {
        within(15.seconds) {
          awaitAssert {
            val probe = TestProbe()
            originalLocations.foreach {
              case (id, ref) =>
                region.tell(Ping(id), probe.ref)
                if (ref.path.address == firstAddress)
                  probe.expectMsgType[ActorRef](1.second) should not be (ref)
                else
                  probe.expectMsg(1.second, ref) // should not move
            }
          }
        }
      }

      enterBarrier("after-4")
    }

  }
}
