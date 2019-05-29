/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor.{ Actor, ActorRef, Props }
import akka.cluster.Cluster
import akka.cluster.sharding.InactiveEntityPassivationSpec.Entity.GotIt
import akka.testkit.{ AkkaSpec, TestProbe }
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config

object InactiveEntityPassivationSpec {

  val config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.classic.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.actor.serialize-messages = off
    """)

  val enabledConfig = ConfigFactory.parseString("""
    akka.cluster.sharding.passivate-idle-entity-after = 3 s
    """).withFallback(config)

  val disabledConfig =
    ConfigFactory.parseString("""akka.cluster.sharding.passivate-idle-entity-after = off""").withFallback(config)

  object Passivate
  object Entity {
    def props(probe: ActorRef) = Props(new Entity(probe))
    case class GotIt(id: String, msg: Any, when: Long)
  }
  class Entity(probe: ActorRef) extends Actor {

    def id = context.self.path.name

    def receive = {
      case Passivate =>
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

abstract class AbstractInactiveEntityPassivationSpec(c: Config) extends AkkaSpec(c) {
  import InactiveEntityPassivationSpec._

  private val smallTolerance = 300.millis

  private val settings = ClusterShardingSettings(system)

  def start(probe: TestProbe): ActorRef = {
    // single node cluster
    Cluster(system).join(Cluster(system).selfAddress)
    ClusterSharding(system).start(
      "myType",
      InactiveEntityPassivationSpec.Entity.props(probe.ref),
      settings,
      extractEntityId,
      extractShardId,
      ClusterSharding(system).defaultShardAllocationStrategy(settings),
      Passivate)
  }

  def timeUntilPassivate(region: ActorRef, probe: TestProbe): FiniteDuration = {
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

    val timeSinceOneSawAMessage = (System.nanoTime() - timeOneSawMessage).nanos
    (settings.passivateIdleEntityAfter - timeSinceOneSawAMessage) + smallTolerance
  }
}

class InactiveEntityPassivationSpec
    extends AbstractInactiveEntityPassivationSpec(InactiveEntityPassivationSpec.enabledConfig) {
  "Passivation of inactive entities" must {
    "passivate entities when they haven't seen messages for the configured duration" in {
      val probe = TestProbe()
      val region = start(probe)

      // make sure "1" hasn't seen a message in 3 seconds and passivates
      probe.expectNoMessage(timeUntilPassivate(region, probe))

      // but it can be re activated
      region ! 1
      region ! 2
      Set(probe.expectMsgType[GotIt], probe.expectMsgType[GotIt]).map(_.id) should ===(Set("1", "2"))
    }
  }
}

class DisabledInactiveEntityPassivationSpec
    extends AbstractInactiveEntityPassivationSpec(InactiveEntityPassivationSpec.disabledConfig) {
  "Passivation of inactive entities" must {
    "not passivate when passivation is disabled" in {
      val probe = TestProbe()
      val region = start(probe)
      probe.expectNoMessage(timeUntilPassivate(region, probe))
    }
  }
}
