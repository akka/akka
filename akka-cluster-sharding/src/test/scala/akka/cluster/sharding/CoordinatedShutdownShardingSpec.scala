/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import scala.concurrent.Future

import scala.concurrent.duration._
import akka.Done
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.testkit.AkkaSpec
import akka.testkit.TestActors.EchoActor
import akka.testkit.TestProbe

object CoordinatedShutdownShardingSpec {
  val config =
    """
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    """

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: Int ⇒ (msg.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case msg: Int ⇒ (msg % 10).toString
  }
}

class CoordinatedShutdownShardingSpec extends AkkaSpec(CoordinatedShutdownShardingSpec.config) {
  import CoordinatedShutdownShardingSpec._

  val sys1 = ActorSystem(system.name, system.settings.config)
  val sys2 = ActorSystem(system.name, system.settings.config)
  val sys3 = system

  val region1 = ClusterSharding(sys1).start("type1", Props[EchoActor](), ClusterShardingSettings(sys1),
    extractEntityId, extractShardId)
  val region2 = ClusterSharding(sys2).start("type1", Props[EchoActor](), ClusterShardingSettings(sys2),
    extractEntityId, extractShardId)
  val region3 = ClusterSharding(sys3).start("type1", Props[EchoActor](), ClusterShardingSettings(sys3),
    extractEntityId, extractShardId)

  val probe1 = TestProbe()(sys1)
  val probe2 = TestProbe()(sys2)
  val probe3 = TestProbe()(sys3)

  CoordinatedShutdown(sys1).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "unbind") { () ⇒
    probe1.ref ! "CS-unbind-1"
    Future.successful(Done)
  }
  CoordinatedShutdown(sys2).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "unbind") { () ⇒
    probe2.ref ! "CS-unbind-2"
    Future.successful(Done)
  }
  CoordinatedShutdown(sys3).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "unbind") { () ⇒
    probe1.ref ! "CS-unbind-3"
    Future.successful(Done)
  }

  override def beforeTermination(): Unit = {
    shutdown(sys1)
    shutdown(sys2)
  }

  def pingEntities(): Unit = {
    region3.tell(1, probe3.ref)
    probe3.expectMsg(10.seconds, 1)
    region3.tell(2, probe3.ref)
    probe3.expectMsg(2)
    region3.tell(3, probe3.ref)
    probe3.expectMsg(3)
  }

  "Sharding and CoordinatedShutdown" must {
    "init cluster" in {
      Cluster(sys1).join(Cluster(sys1).selfAddress) // coordinator will initially run on sys2
      awaitAssert(Cluster(sys1).selfMember.status should ===(MemberStatus.Up))

      Cluster(sys2).join(Cluster(sys1).selfAddress)
      within(10.seconds) {
        awaitAssert {
          Cluster(sys1).state.members.size should ===(2)
          Cluster(sys1).state.members.map(_.status) should ===(Set(MemberStatus.Up))
          Cluster(sys2).state.members.size should ===(2)
          Cluster(sys2).state.members.map(_.status) should ===(Set(MemberStatus.Up))
        }
      }

      Cluster(sys3).join(Cluster(sys1).selfAddress)
      within(10.seconds) {
        awaitAssert {
          Cluster(sys1).state.members.size should ===(3)
          Cluster(sys1).state.members.map(_.status) should ===(Set(MemberStatus.Up))
          Cluster(sys2).state.members.size should ===(3)
          Cluster(sys2).state.members.map(_.status) should ===(Set(MemberStatus.Up))
          Cluster(sys3).state.members.size should ===(3)
          Cluster(sys3).state.members.map(_.status) should ===(Set(MemberStatus.Up))
        }
      }

      pingEntities()
    }

    "run coordinated shutdown when leaving" in {
      Cluster(sys3).leave(Cluster(sys1).selfAddress)
      probe1.expectMsg("CS-unbind-1")

      within(10.seconds) {
        awaitAssert {
          Cluster(sys2).state.members.size should ===(2)
          Cluster(sys3).state.members.size should ===(2)
        }
      }
      within(10.seconds) {
        awaitAssert {
          Cluster(sys1).isTerminated should ===(true)
          sys1.whenTerminated.isCompleted should ===(true)
        }
      }

      pingEntities()
    }

    "run coordinated shutdown when downing" in {
      Cluster(sys3).down(Cluster(sys2).selfAddress)
      probe2.expectMsg("CS-unbind-2")

      within(10.seconds) {
        awaitAssert {
          Cluster(system).state.members.size should ===(1)
        }
      }
      within(10.seconds) {
        awaitAssert {
          Cluster(sys2).isTerminated should ===(true)
          sys2.whenTerminated.isCompleted should ===(true)
        }
      }

      pingEntities()
    }
  }
}
