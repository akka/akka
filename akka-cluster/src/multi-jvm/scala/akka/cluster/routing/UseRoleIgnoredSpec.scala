/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.routing

import akka.actor._
import akka.cluster.MultiNodeClusterSpec
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.GetRoutees
import akka.routing.RoundRobinGroup
import akka.routing.RoundRobinPool
import akka.routing.Routees
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit._
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

object UseRoleIgnoredMultiJvmSpec extends MultiNodeConfig {

  class SomeActor(routeeType: RouteeType) extends Actor with ActorLogging {
    log.info("Starting on {}", self.path.address)

    def this() = this(PoolRoutee)

    def receive = {
      case msg ⇒
        log.info("msg = {}", msg)
        sender() ! Reply(routeeType, self)
    }
  }

  final case class Reply(routeeType: RouteeType, ref: ActorRef)

  sealed trait RouteeType extends Serializable
  object PoolRoutee extends RouteeType
  object GroupRoutee extends RouteeType

  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(first)(ConfigFactory.parseString("""akka.cluster.roles =["a", "c"]"""))
  nodeConfig(second, third)(ConfigFactory.parseString("""akka.cluster.roles =["b", "c"]"""))

}

class UseRoleIgnoredMultiJvmNode1 extends UseRoleIgnoredSpec
class UseRoleIgnoredMultiJvmNode2 extends UseRoleIgnoredSpec
class UseRoleIgnoredMultiJvmNode3 extends UseRoleIgnoredSpec

abstract class UseRoleIgnoredSpec extends MultiNodeSpec(UseRoleIgnoredMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with DefaultTimeout {
  import akka.cluster.routing.UseRoleIgnoredMultiJvmSpec._

  def receiveReplies(routeeType: RouteeType, expectedReplies: Int): Map[Address, Int] = {
    val zero = Map.empty[Address, Int] ++ roles.map(address(_) -> 0)
    (receiveWhile(5 seconds, messages = expectedReplies) {
      case Reply(`routeeType`, ref) ⇒ fullAddress(ref)
    }).foldLeft(zero) {
      case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
    }
  }

  /**
   * Fills in self address for local ActorRef
   */
  private def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  def currentRoutees(router: ActorRef) =
    Await.result(router ? GetRoutees, timeout.duration).asInstanceOf[Routees].routees

  "A cluster" must {
    "start cluster" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)
      runOn(first) { info("first, roles: " + cluster.selfRoles) }
      runOn(second) { info("second, roles: " + cluster.selfRoles) }
      runOn(third) { info("third, roles: " + cluster.selfRoles) }

      // routees for the group routers
      system.actorOf(Props(classOf[SomeActor], GroupRoutee), "foo")
      system.actorOf(Props(classOf[SomeActor], GroupRoutee), "bar")

      enterBarrier("after-1")
    }

    "pool local: off, roles: off, 6 => 0,2,2" taggedAs LongRunningTest in {

      runOn(first) {
        val role = Some("b")

        val router = system.actorOf(ClusterRouterPool(
          RoundRobinPool(nrOfInstances = 6),
          ClusterRouterPoolSettings(totalInstances = 6, maxInstancesPerNode = 2, allowLocalRoutees = false, useRole = role)).
          props(Props[SomeActor]),
          "router-2")

        awaitAssert(currentRoutees(router).size should ===(4))

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router ! s"hit-$i"
        }

        val replies = receiveReplies(PoolRoutee, iterationCount)

        replies(first) should ===(0) // should not be deployed locally, does not have required role
        replies(second) should be > 0
        replies(third) should be > 0
        replies.values.sum should ===(iterationCount)
      }

      enterBarrier("after-2")
    }

    "group local: off, roles: off, 6 => 0,2,2" taggedAs LongRunningTest in {

      runOn(first) {
        val role = Some("b")

        val router = system.actorOf(ClusterRouterGroup(
          RoundRobinGroup(paths = Nil),
          ClusterRouterGroupSettings(totalInstances = 6, routeesPaths = List("/user/foo", "/user/bar"),
            allowLocalRoutees = false, useRole = role)).props,
          "router-2b")

        awaitAssert(currentRoutees(router).size should ===(4))

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router ! s"hit-$i"
        }

        val replies = receiveReplies(GroupRoutee, iterationCount)

        replies(first) should ===(0) // should not be deployed locally, does not have required role
        replies(second) should be > 0
        replies(third) should be > 0
        replies.values.sum should ===(iterationCount)
      }

      enterBarrier("after-2b")
    }

    "pool local: on, role: b, 6 => 0,2,2" taggedAs LongRunningTest in {

      runOn(first) {
        val role = Some("b")

        val router = system.actorOf(ClusterRouterPool(
          RoundRobinPool(nrOfInstances = 6),
          ClusterRouterPoolSettings(totalInstances = 6, maxInstancesPerNode = 2, allowLocalRoutees = true, useRole = role)).
          props(Props[SomeActor]),
          "router-3")

        awaitAssert(currentRoutees(router).size should ===(4))

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router ! s"hit-$i"
        }

        val replies = receiveReplies(PoolRoutee, iterationCount)

        replies(first) should ===(0) // should not be deployed locally, does not have required role
        replies(second) should be > 0
        replies(third) should be > 0
        replies.values.sum should ===(iterationCount)
      }

      enterBarrier("after-3")
    }

    "group local: on, role: b, 6 => 0,2,2" taggedAs LongRunningTest in {

      runOn(first) {
        val role = Some("b")

        val router = system.actorOf(ClusterRouterGroup(
          RoundRobinGroup(paths = Nil),
          ClusterRouterGroupSettings(totalInstances = 6, routeesPaths = List("/user/foo", "/user/bar"),
            allowLocalRoutees = true, useRole = role)).props,
          "router-3b")

        awaitAssert(currentRoutees(router).size should ===(4))

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router ! s"hit-$i"
        }

        val replies = receiveReplies(GroupRoutee, iterationCount)

        replies(first) should ===(0) // should not be deployed locally, does not have required role
        replies(second) should be > 0
        replies(third) should be > 0
        replies.values.sum should ===(iterationCount)
      }

      enterBarrier("after-3b")
    }

    "pool local: on, role: a, 6 => 2,0,0" taggedAs LongRunningTest in {

      runOn(first) {
        val role = Some("a")

        val router = system.actorOf(ClusterRouterPool(
          RoundRobinPool(nrOfInstances = 6),
          ClusterRouterPoolSettings(totalInstances = 6, maxInstancesPerNode = 2, allowLocalRoutees = true, useRole = role)).
          props(Props[SomeActor]),
          "router-4")

        awaitAssert(currentRoutees(router).size should ===(2))

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router ! s"hit-$i"
        }

        val replies = receiveReplies(PoolRoutee, iterationCount)

        replies(first) should be > 0
        replies(second) should ===(0)
        replies(third) should ===(0)
        replies.values.sum should ===(iterationCount)
      }

      enterBarrier("after-4")
    }

    "group local: on, role: a, 6 => 2,0,0" taggedAs LongRunningTest in {

      runOn(first) {
        val role = Some("a")

        val router = system.actorOf(ClusterRouterGroup(
          RoundRobinGroup(paths = Nil),
          ClusterRouterGroupSettings(totalInstances = 6, routeesPaths = List("/user/foo", "/user/bar"),
            allowLocalRoutees = true, useRole = role)).props,
          "router-4b")

        awaitAssert(currentRoutees(router).size should ===(2))

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router ! s"hit-$i"
        }

        val replies = receiveReplies(GroupRoutee, iterationCount)

        replies(first) should be > 0
        replies(second) should ===(0)
        replies(third) should ===(0)
        replies.values.sum should ===(iterationCount)
      }

      enterBarrier("after-4b")
    }

    "pool local: on, role: c, 6 => 2,2,2" taggedAs LongRunningTest in {

      runOn(first) {
        val role = Some("c")

        val router = system.actorOf(ClusterRouterPool(
          RoundRobinPool(nrOfInstances = 6),
          ClusterRouterPoolSettings(totalInstances = 6, maxInstancesPerNode = 2, allowLocalRoutees = true, useRole = role)).
          props(Props[SomeActor]),
          "router-5")

        awaitAssert(currentRoutees(router).size should ===(6))

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router ! s"hit-$i"
        }

        val replies = receiveReplies(PoolRoutee, iterationCount)

        replies(first) should be > 0
        replies(second) should be > 0
        replies(third) should be > 0
        replies.values.sum should ===(iterationCount)
      }

      enterBarrier("after-5")
    }

    "group local: on, role: c, 6 => 2,2,2" taggedAs LongRunningTest in {

      runOn(first) {
        val role = Some("c")

        val router = system.actorOf(ClusterRouterGroup(
          RoundRobinGroup(paths = Nil),
          ClusterRouterGroupSettings(totalInstances = 6, routeesPaths = List("/user/foo", "/user/bar"),
            allowLocalRoutees = true, useRole = role)).props,
          "router-5b")

        awaitAssert(currentRoutees(router).size should ===(6))

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router ! s"hit-$i"
        }

        val replies = receiveReplies(GroupRoutee, iterationCount)

        replies(first) should be > 0
        replies(second) should be > 0
        replies(third) should be > 0
        replies.values.sum should ===(iterationCount)
      }

      enterBarrier("after-5b")
    }

  }
}
