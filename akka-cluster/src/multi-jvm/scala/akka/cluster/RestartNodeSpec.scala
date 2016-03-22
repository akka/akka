/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import scala.collection.immutable
import scala.concurrent.duration._

import akka.Done
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.Deploy
import akka.actor.Identify
import akka.actor.Props
import akka.actor.RootActorPath
import akka.actor.Terminated
import akka.cluster.MemberStatus._
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object RestartNodeMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster.auto-down-unreachable-after = 5s
      #akka.remote.use-passive-connections = off
      """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

  /**
   * This was used together with sleep in EndpointReader before deliverAndAck
   * to reproduce issue with misaligned ACKs when restarting system,
   * issue #19780
   */
  class Watcher(a: Address, replyTo: ActorRef) extends Actor {
    context.actorSelection(RootActorPath(a) / "user" / "address-receiver") ! Identify(None)

    def receive = {
      case ActorIdentity(None, Some(ref)) ⇒
        context.watch(ref)
        replyTo ! Done
      case t: Terminated ⇒
    }
  }
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
      if (secondSystem.whenTerminated.isCompleted)
        shutdown(restartedSecondSystem)
      else
        shutdown(secondSystem)
    }
    super.afterAll()
  }

  "Cluster nodes" must {
    "be able to restart and join again" taggedAs LongRunningTest in within(60.seconds) {
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
          expectMsg(5.seconds, "ok")
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
        awaitAssert(Cluster(secondSystem).readView.members.size should ===(3))
        awaitAssert(Cluster(secondSystem).readView.members.map(_.status) should ===(Set(Up)))
      }
      enterBarrier("started")

      // shutdown secondSystem
      runOn(second) {
        // send system message just before shutdown, reproducer for issue #19780
        secondSystem.actorOf(Props(classOf[Watcher], address(first), testActor), "testwatcher")
        expectMsg(Done)

        shutdown(secondSystem, remaining)
      }
      enterBarrier("second-shutdown")

      // then immediately start restartedSecondSystem, which has the same address as secondSystem
      runOn(second) {
        Cluster(restartedSecondSystem).joinSeedNodes(seedNodes)
        awaitAssert(Cluster(restartedSecondSystem).readView.members.size should ===(3))
        awaitAssert(Cluster(restartedSecondSystem).readView.members.map(_.status) should ===(Set(Up)))
      }
      runOn(first, third) {
        awaitAssert {
          Cluster(system).readView.members.size should ===(3)
          Cluster(system).readView.members.exists { m ⇒
            m.address == secondUniqueAddress.address && m.uniqueAddress.uid != secondUniqueAddress.uid
          }
        }
      }
      enterBarrier("second-restarted")

    }

  }
}
