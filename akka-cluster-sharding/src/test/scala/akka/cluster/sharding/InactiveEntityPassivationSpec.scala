/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.{ Actor, ActorRef, Props }
import akka.cluster.Cluster
import akka.cluster.sharding.InactiveEntityPassivationSpec.Entity.GotIt
import akka.testkit.{ AkkaSpec, TestProbe }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object InactiveEntityPassivationSpec {
  val config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.cluster.sharding.passivate-idle-entity-after = 3 s
    akka.actor.serialize-messages = off
    """)

  object Passivate
  object Entity {
    def props(probe: ActorRef) = Props(new Entity(probe))
    case class GotIt(id: String, msg: Any, when: Long)
  }
  class Entity(probe: ActorRef) extends Actor {

    def id = context.self.path.name

    def receive = {
      case Passivate =>
        probe ! id + " passivating"
        context.stop(self)
      case msg => probe ! GotIt(id, msg, System.nanoTime())
    }

  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: Int => (msg.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case msg: Int => (msg % 10).toString
  }

}

class InactiveEntityPassivationSpec extends AkkaSpec(InactiveEntityPassivationSpec.config) {
  import InactiveEntityPassivationSpec._

  "Passivation of inactive entities" must {

    "passivate entities when they haven't seen messages for the configured duration" in {
      // single node cluster
      Cluster(system).join(Cluster(system).selfAddress)
      val probe = TestProbe()
      val settings = ClusterShardingSettings(system)
      val region = ClusterSharding(system).start(
        "myType",
        InactiveEntityPassivationSpec.Entity.props(probe.ref),
        settings,
        extractEntityId,
        extractShardId,
        ClusterSharding(system).defaultShardAllocationStrategy(settings),
        Passivate)

      region ! 1
      region ! 2
      val responses = Set(probe.expectMsgType[GotIt], probe.expectMsgType[GotIt])
      responses.map(_.id) should ===(Set("1", "2"))
      val timeOneSawMessage = responses.find(_.id == "1").get.when
      Thread.sleep(1000)
      region ! 2
      probe.expectMsgType[GotIt].id should ===("2")
      Thread.sleep(1000)
      region ! 2
      probe.expectMsgType[GotIt].id should ===("2")

      // make sure "1" hasn't seen a message in 3 seconds and passivates
      val timeSinceOneSawAMessage = (System.nanoTime() - timeOneSawMessage).nanos
      probe.expectNoMessage(3.seconds - timeSinceOneSawAMessage)
      probe.expectMsg("1 passivating")

      // but it can be re activated just fine:
      region ! 1
      region ! 2
      Set(probe.expectMsgType[GotIt], probe.expectMsgType[GotIt]).map(_.id) should ===(Set("1", "2"))

    }
  }

}
