/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.MemberStatus
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.serialization.jackson.CborSerializable
import akka.testkit._
import akka.util.ccompat._

@ccompatUsedUntil213
object ClusterShardCoordinatorDowning2Spec {
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
    case _ => throw new IllegalArgumentException()
  }
}

abstract class ClusterShardCoordinatorDowning2SpecConfig(mode: String)
    extends MultiNodeClusterShardingConfig(
      mode,
      loglevel = "INFO",
      additionalConfig = """
        akka.cluster.sharding.rebalance-interval = 120 s
        # setting down-removal-margin, for testing of issue #29131
        akka.cluster.down-removal-margin = 3 s
        akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 3s
      """) {
  val first = role("first")
  val second = role("second")

  testTransport(on = true)

}

object PersistentClusterShardCoordinatorDowning2SpecConfig
    extends ClusterShardCoordinatorDowning2SpecConfig(ClusterShardingSettings.StateStoreModePersistence)
object DDataClusterShardCoordinatorDowning2SpecConfig
    extends ClusterShardCoordinatorDowning2SpecConfig(ClusterShardingSettings.StateStoreModeDData)

class PersistentClusterShardCoordinatorDowning2Spec
    extends ClusterShardCoordinatorDowning2Spec(PersistentClusterShardCoordinatorDowning2SpecConfig)
class DDataClusterShardCoordinatorDowning2Spec
    extends ClusterShardCoordinatorDowning2Spec(DDataClusterShardCoordinatorDowning2SpecConfig)

class PersistentClusterShardCoordinatorDowning2MultiJvmNode1 extends PersistentClusterShardCoordinatorDowning2Spec
class PersistentClusterShardCoordinatorDowning2MultiJvmNode2 extends PersistentClusterShardCoordinatorDowning2Spec

class DDataClusterShardCoordinatorDowning2MultiJvmNode1 extends DDataClusterShardCoordinatorDowning2Spec
class DDataClusterShardCoordinatorDowning2MultiJvmNode2 extends DDataClusterShardCoordinatorDowning2Spec

abstract class ClusterShardCoordinatorDowning2Spec(multiNodeConfig: ClusterShardCoordinatorDowning2SpecConfig)
    extends MultiNodeClusterShardingSpec(multiNodeConfig)
    with ImplicitSender {
  import multiNodeConfig._

  import ClusterShardCoordinatorDowning2Spec._

  def startSharding(): Unit = {
    startSharding(
      system,
      typeName = "Entity",
      entityProps = Props[Entity](),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  s"Cluster sharding ($mode) with down member, scenario 2" must {

    "join cluster" in within(20.seconds) {
      startPersistenceIfNeeded(startOn = first, setStoreOn = Seq(first, second))

      join(first, first, onJoinedRunOnFrom = startSharding())
      join(second, first, onJoinedRunOnFrom = startSharding(), assertNodeUp = false)

      // all Up, everywhere before continuing
      runOn(first, second) {
        awaitAssert {
          cluster.state.members.size should ===(2)
          cluster.state.members.unsorted.map(_.status) should ===(Set(MemberStatus.Up))
        }
      }

      enterBarrier("after-2")
    }

    "initialize shards" in {
      runOn(first) {
        val shardLocations = system.actorOf(Props[ShardLocations](), "shardLocations")
        val locations = (for (n <- 1 to 4) yield {
          val id = n.toString
          region ! Ping(id)
          id -> expectMsgType[ActorRef]
        }).toMap
        shardLocations ! Locations(locations)
        system.log.debug("Original locations: {}", locations)
      }
      enterBarrier("after-3")
    }

    "recover after downing other node (not coordinator)" in within(20.seconds) {
      val secondAddress = address(second)

      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
      }

      Thread.sleep(3000)

      runOn(first) {
        cluster.down(second)
        awaitAssert {
          cluster.state.members.size should ===(1)
        }

        // start a few more new shards, could be allocated to second but should notice that it's terminated
        val additionalLocations =
          awaitAssert {
            val probe = TestProbe()
            (for (n <- 5 to 8) yield {
              val id = n.toString
              region.tell(Ping(id), probe.ref)
              id -> probe.expectMsgType[ActorRef](1.second)
            }).toMap
          }
        system.log.debug("Additional locations: {}", additionalLocations)

        system.actorSelection(node(first) / "user" / "shardLocations") ! GetLocations
        val Locations(originalLocations) = expectMsgType[Locations]

        awaitAssert {
          val probe = TestProbe()
          (originalLocations ++ additionalLocations).foreach {
            case (id, ref) =>
              region.tell(Ping(id), probe.ref)
              if (ref.path.address == secondAddress) {
                val newRef = probe.expectMsgType[ActorRef](1.second)
                newRef should not be (ref)
                system.log.debug("Moved [{}] from [{}] to [{}]", id, ref, newRef)
              } else
                probe.expectMsg(1.second, ref) // should not move
          }
        }
      }

      enterBarrier("after-4")
    }

  }
}
