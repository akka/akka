package akka.cluster.sharding

import akka.actor.{ Actor, Deploy, Props }
import akka.cluster.sharding.Shard.RestartEntities
import akka.cluster.sharding.ShardRegion.EntityId
import akka.testkit.{ AkkaSpec, TestProbe }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.language.postfixOps

class ConstantRateEntityRecoveryStrategySpec extends AkkaSpec {
  val config = ConfigFactory.parseString(
    """
      |frequency = 500ms
      |number-of-entities = 2
    """.stripMargin)

  val strategy = new ConstantRateEntityRecoveryConfigurator().create(config)

  "ConstantRateEntityRecoveryStrategy" must {
    "recover entities" in {
      val probe = TestProbe()
      val probeRef = probe.ref
      val shard = system.actorOf(Props(new Actor {
        val entities = Set[EntityId]("1", "2", "3", "4", "5")
        def receive = {
          case "recover"          ⇒ strategy.recoverEntities(context, entities)
          case RestartEntities(e) ⇒ probeRef ! e
        }
      }).withDeploy(Deploy.local))

      shard ! "recover"
      probe.expectNoMsg(450 millis)
      probe.expectMsg(Set[EntityId]("4", "5"))
      probe.expectNoMsg(450 millis)
      probe.expectMsg(Set[EntityId]("1", "2"))
      probe.expectNoMsg(450 millis)
      probe.expectMsg(Set[EntityId]("3"))
    }

    "not recover when no entities to recover" in {
      val probe = TestProbe()
      val probeRef = probe.ref
      val shard = system.actorOf(Props(new Actor {
        def receive = {
          case "recover"          ⇒ strategy.recoverEntities(context, Set[EntityId]())
          case RestartEntities(e) ⇒ probeRef ! e
        }
      }).withDeploy(Deploy.local))

      shard ! "recover"
      probe.expectNoMsg(1 second)
    }
  }
}
