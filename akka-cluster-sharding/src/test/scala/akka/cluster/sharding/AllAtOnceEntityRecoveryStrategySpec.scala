package akka.cluster.sharding

import akka.actor.{ Actor, Deploy, Props }
import akka.cluster.sharding.Shard.RestartEntities
import akka.cluster.sharding.ShardRegion.EntityId
import akka.testkit.{ AkkaSpec, TestProbe }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.language.postfixOps

class AllAtOnceEntityRecoveryStrategySpec extends AkkaSpec {
  val strategy = new AllAtOnceEntityRecoveryConfigurator().create(ConfigFactory.empty())

  "AllAtOnceEntityRecoveryStrategy" must {
    "recover entities" in {
      val probe = TestProbe()
      val probeRef = probe.ref
      val shard = system.actorOf(Props(new Actor {
        def receive = {
          case "recover"          ⇒ strategy.recoverEntities(context, Set[EntityId]("1", "2", "3"))
          case RestartEntities(e) ⇒ probeRef ! e
        }
      }).withDeploy(Deploy.local))

      shard ! "recover"
      probe.expectMsg(Set[EntityId]("1", "2", "3"))
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