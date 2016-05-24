/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.singleton

import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import akka.testkit.{ TestProbe, TestKit }
import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.cluster.Cluster
import scala.concurrent.duration._

class ClusterSingletonProxySpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  import ClusterSingletonProxySpec._

  val seed = new ActorSys()
  seed.cluster.join(seed.cluster.selfAddress)

  val testSystems = (0 until 4).map(_ ⇒ new ActorSys(joinTo = Some(seed.cluster.selfAddress))) :+ seed

  "The cluster singleton proxy" must {
    "correctly identify the singleton" in {
      testSystems.foreach(_.testProxy("Hello"))
      testSystems.foreach(_.testProxy("World"))
    }
  }

  override def afterAll() = testSystems.foreach(_.system.terminate())
}

object ClusterSingletonProxySpec {

  class ActorSys(name: String = "ClusterSingletonProxySystem", joinTo: Option[Address] = None)
    extends TestKit(ActorSystem(name, ConfigFactory.parseString(cfg))) {

    val cluster = Cluster(system)
    joinTo.foreach(address ⇒ cluster.join(address))

    cluster.registerOnMemberUp {
      system.actorOf(
        ClusterSingletonManager.props(
          singletonProps = Props[Singleton],
          terminationMessage = PoisonPill,
          settings = ClusterSingletonManagerSettings(system).withRemovalMargin(5.seconds)),
        name = "singletonManager")
    }

    val proxy = system.actorOf(ClusterSingletonProxy.props(
      "user/singletonManager",
      settings = ClusterSingletonProxySettings(system)), s"singletonProxy-${cluster.selfAddress.port.getOrElse(0)}")

    def testProxy(msg: String) {
      val probe = TestProbe()
      probe.send(proxy, msg)
      // 25 seconds to make sure the singleton was started up
      probe.expectMsg(25.seconds, "Got " + msg)
    }
  }

  val cfg = """akka {

                loglevel = INFO

                cluster {
                  auto-down-unreachable-after = 10s

                  min-nr-of-members = 2
                }

                actor.provider = "akka.cluster.ClusterActorRefProvider"

                remote {
                  log-remote-lifecycle-events = off
                  netty.tcp {
                    hostname = "127.0.0.1"
                    port = 0
                  }
                }
              }
            """

  class Singleton extends Actor with ActorLogging {

    log.info("Singleton created on {}", Cluster(context.system).selfAddress)

    def receive: Actor.Receive = {
      case msg ⇒
        sender() ! "Got " + msg
    }
  }

}
