/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.duration._
import akka.actor.Address
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Actor
import akka.actor.RootActorPath
import akka.cluster.MemberStatus._
import akka.actor.Deploy

object RestartNodeMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.auto-down-unreachable-after = 5s")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class RestartNodeMultiJvmNode1 extends RestartNodeSpec
class RestartNodeMultiJvmNode2 extends RestartNodeSpec
class RestartNodeMultiJvmNode3 extends RestartNodeSpec

abstract class RestartNodeSpec
  extends MultiNodeSpec(RestartNodeMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender {

  import RestartNodeMultiJvmSpec._

  @volatile var secondUniqueAddress: UniqueAddress = _

  // use a separate ActorSystem, to be able to simulate restart
  lazy val secondSystem = ActorSystem(system.name, system.settings.config)

  def seedNodes: immutable.IndexedSeq[Address] = Vector(first, secondUniqueAddress.address, third)

  lazy val restartedSecondSystem = ActorSystem(system.name,
    ConfigFactory.parseString("akka.remote.netty.tcp.port=" + secondUniqueAddress.address.port.get).
      withFallback(system.settings.config))

  override def afterAll(): Unit = {
    runOn(second) {
      if (secondSystem.isTerminated)
        shutdown(restartedSecondSystem)
      else
        shutdown(secondSystem)
    }
    super.afterAll()
  }

  "Cluster nodes" must {
    "be able to restart and join again" taggedAs LongRunningTest in within(60 seconds) {
      // secondSystem is a separate ActorSystem, to be able to simulate restart
      // we must transfer its address to first
      runOn(first, third) {
        system.actorOf(Props(new Actor {
          def receive = {
            case a: UniqueAddress ⇒
              secondUniqueAddress = a
              sender() ! "ok"
          }
        }).withDeploy(Deploy.local), name = "address-receiver")
        enterBarrier("second-address-receiver-ready")
      }

      runOn(second) {
        enterBarrier("second-address-receiver-ready")
        secondUniqueAddress = Cluster(secondSystem).selfUniqueAddress
        List(first, third) foreach { r ⇒
          system.actorSelection(RootActorPath(r) / "user" / "address-receiver") ! secondUniqueAddress
          expectMsg(5 seconds, "ok")
        }
      }
      enterBarrier("second-address-transfered")

      // now we can join first, secondSystem, third together
      runOn(first, third) {
        cluster.joinSeedNodes(seedNodes)
        awaitMembersUp(3)
      }
      runOn(second) {
        Cluster(secondSystem).joinSeedNodes(seedNodes)
        awaitAssert(Cluster(secondSystem).readView.members.size should be(3))
        awaitAssert(Cluster(secondSystem).readView.members.map(_.status) should be(Set(Up)))
      }
      enterBarrier("started")

      // shutdown secondSystem
      runOn(second) {
        shutdown(secondSystem, remaining)
      }
      enterBarrier("second-shutdown")

      // then immediately start restartedSecondSystem, which has the same address as secondSystem
      runOn(second) {
        Cluster(restartedSecondSystem).joinSeedNodes(seedNodes)
        awaitAssert(Cluster(restartedSecondSystem).readView.members.size should be(3))
        awaitAssert(Cluster(restartedSecondSystem).readView.members.map(_.status) should be(Set(Up)))
      }
      runOn(first, third) {
        awaitAssert {
          Cluster(system).readView.members.size should be(3)
          Cluster(system).readView.members.exists { m ⇒
            m.address == secondUniqueAddress.address && m.uniqueAddress.uid != secondUniqueAddress.uid
          }
        }
      }
      enterBarrier("second-restarted")

    }

  }
}
