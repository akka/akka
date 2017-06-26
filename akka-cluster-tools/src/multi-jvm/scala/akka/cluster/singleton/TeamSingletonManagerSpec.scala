/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.singleton

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import akka.actor.{ Actor, ActorLogging, Address, PoisonPill, Props }
import akka.cluster.Cluster

import akka.testkit.ImplicitSender
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }

object TeamSingletonManagerSpec extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.actor.serialize-creators = off
    akka.remote.log-remote-lifecycle-events = off"""))

  nodeConfig(controller)(
    ConfigFactory.parseString("akka.cluster.team = one\n" +
      "akka.cluster.roles = [ ]"))
  nodeConfig(first) {
    ConfigFactory.parseString("akka.cluster.team = one\n" +
      "akka.cluster.roles = [ worker ]")
  }
  nodeConfig(second)(
    ConfigFactory.parseString("akka.cluster.team = two\n" +
      "akka.cluster.roles = [ worker ]"))
  nodeConfig(third)(
    ConfigFactory.parseString("akka.cluster.team = two\n" +
      "akka.cluster.roles = [ worker ]"))
}

class TeamSingletonManagerMultiJvmNode1 extends TeamSingletonManagerSpec
class TeamSingletonManagerMultiJvmNode2 extends TeamSingletonManagerSpec
class TeamSingletonManagerMultiJvmNode3 extends TeamSingletonManagerSpec
class TeamSingletonManagerMultiJvmNode4 extends TeamSingletonManagerSpec

class TeamSingleton extends Actor with ActorLogging {
  import TeamSingleton._

  val cluster = Cluster(context.system)

  override def receive: Receive = {
    case Ping ⇒
      sender() ! Pong(cluster.settings.Team, cluster.selfAddress, cluster.selfRoles)
  }
}
object TeamSingleton {
  case object Ping
  case class Pong(fromTeam: String, fromAddress: Address, roles: Set[String])
}

abstract class TeamSingletonManagerSpec extends MultiNodeSpec(TeamSingletonManagerSpec) with STMultiNodeSpec with ImplicitSender {
  import TeamSingletonManagerSpec._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)
  cluster.join(node(controller).address)
  enterBarrier("nodes-joined")

  val worker = "worker"

  "A SingletonManager in a team" must {
    "start a singleton instance for each team" in {

      runOn(first, second, third) {
        system.actorOf(
          ClusterSingletonManager.props(
            Props[TeamSingleton](),
            PoisonPill,
            ClusterSingletonManagerSettings(system).withRole(worker)),
          "singletonManager")
      }

      val proxy = system.actorOf(ClusterSingletonProxy.props(
        "/user/singletonManager",
        ClusterSingletonProxySettings(system).withRole(worker)))

      enterBarrier("managers-started")

      proxy ! TeamSingleton.Ping
      val pong = expectMsgType[TeamSingleton.Pong](10.seconds)

      enterBarrier("pongs-received")

      pong.fromTeam should equal(Cluster(system).settings.Team)
      pong.roles should contain(worker)
      runOn(controller, first) {
        pong.roles should contain("team-one")
      }
      runOn(second, third) {
        pong.roles should contain("team-two")
      }

      enterBarrier("after-1")
    }

  }
}
