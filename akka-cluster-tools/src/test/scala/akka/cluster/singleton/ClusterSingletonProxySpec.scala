/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.singleton

import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import akka.testkit.{ TestKit, TestProbe }
import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.cluster.Cluster
import scala.concurrent.duration._

class ClusterSingletonProxySpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  import ClusterSingletonProxySpec._

  val seed = new ActorSys()

  val testSystems = {
    val joiners = (0 until 4).map(n => new ActorSys(joinTo = Some(seed.cluster.selfAddress)))
    joiners :+ seed
  }

  "The cluster singleton proxy" must {
    "correctly identify the singleton" in {
      testSystems.foreach(_.testProxy("Hello"))
      testSystems.foreach(_.testProxy("World"))
    }
  }

  override def afterAll(): Unit = testSystems.foreach { sys =>
    TestKit.shutdownActorSystem(sys.system)
  }
}

object ClusterSingletonProxySpec {

  class ActorSys(name: String = "ClusterSingletonProxySystem", joinTo: Option[Address] = None)
      extends TestKit(ActorSystem(name, ConfigFactory.parseString(cfg))) {

    val cluster = Cluster(system)
    cluster.join(joinTo.getOrElse(cluster.selfAddress))

    cluster.registerOnMemberUp {
      system.actorOf(
        ClusterSingletonManager.props(
          singletonProps = Props[Singleton],
          terminationMessage = PoisonPill,
          settings = ClusterSingletonManagerSettings(system).withRemovalMargin(5.seconds)),
        name = "singletonManager")
    }

    val proxy = system.actorOf(
      ClusterSingletonProxy.props("user/singletonManager", settings = ClusterSingletonProxySettings(system)),
      s"singletonProxy-${cluster.selfAddress.port.getOrElse(0)}")

    def testProxy(msg: String): Unit = {
      val probe = TestProbe()
      probe.send(proxy, msg)
      // 25 seconds to make sure the singleton was started up
      probe.expectMsg(25.seconds, s"while testing the proxy from ${cluster.selfAddress}", "Got " + msg)
    }
  }

  val cfg = """
    akka {
      loglevel = INFO
      cluster.jmx.enabled = off
      actor.provider = "cluster"
      remote {
        log-remote-lifecycle-events = off
        netty.tcp {
          hostname = "127.0.0.1"
          port = 0
        }
        artery.canonical {
          hostname  = "127.0.0.1"
          port = 0
        }
      }
    }
  """

  class Singleton extends Actor with ActorLogging {

    log.info("Singleton created on {}", Cluster(context.system).selfAddress)

    def receive: Actor.Receive = {
      case msg =>
        log.info(s"Got $msg")
        sender() ! "Got " + msg
    }
  }

}
