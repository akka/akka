/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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

object RestartFirstSeedNodeMultiJvmSpec extends MultiNodeConfig {
  val seed1 = role("seed1")
  val seed2 = role("seed2")
  val seed3 = role("seed3")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster {
        auto-join = off
        auto-down = on
      }
    """)).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class RestartFirstSeedNodeMultiJvmNode1 extends RestartFirstSeedNodeSpec
class RestartFirstSeedNodeMultiJvmNode2 extends RestartFirstSeedNodeSpec
class RestartFirstSeedNodeMultiJvmNode3 extends RestartFirstSeedNodeSpec

abstract class RestartFirstSeedNodeSpec
  extends MultiNodeSpec(RestartFirstSeedNodeMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender {

  import RestartFirstSeedNodeMultiJvmSpec._

  @volatile var seedNode1Address: Address = _

  // use a separate ActorSystem, to be able to simulate restart
  lazy val seed1System = ActorSystem(system.name, system.settings.config)

  def missingSeed = address(seed3).copy(port = Some(61313))
  def seedNodes: immutable.IndexedSeq[Address] = Vector(seedNode1Address, seed2, seed3, missingSeed)

  lazy val restartedSeed1System = ActorSystem(system.name,
    ConfigFactory.parseString("akka.remote.netty.tcp.port=" + seedNodes.head.port.get).
      withFallback(system.settings.config))

  override def afterAll(): Unit = {
    runOn(seed1) {
      if (seed1System.isTerminated)
        restartedSeed1System.shutdown()
      else
        seed1System.shutdown()
    }
    super.afterAll()
  }

  "Cluster seed nodes" must {
    "be able to restart first seed node and join other seed nodes" taggedAs LongRunningTest in within(40 seconds) {
      // seed1System is a separate ActorSystem, to be able to simulate restart
      // we must transfer its address to seed2 and seed3
      runOn(seed2, seed3) {
        system.actorOf(Props(new Actor {
          def receive = {
            case a: Address ⇒
              seedNode1Address = a
              sender ! "ok"
          }
        }), name = "address-receiver")
        enterBarrier("seed1-address-receiver-ready")
      }

      runOn(seed1) {
        enterBarrier("seed1-address-receiver-ready")
        seedNode1Address = Cluster(seed1System).selfAddress
        List(seed2, seed3) foreach { r ⇒
          system.actorFor(RootActorPath(r) / "user" / "address-receiver") ! seedNode1Address
          expectMsg(5 seconds, "ok")
        }
      }
      enterBarrier("seed1-address-transfered")

      // now we can join seed1System, seed2, seed3 together
      runOn(seed1) {
        Cluster(seed1System).joinSeedNodes(seedNodes)
        awaitCond(Cluster(seed1System).readView.members.size == 3)
        awaitCond(Cluster(seed1System).readView.members.forall(_.status == Up))
      }
      runOn(seed2, seed3) {
        cluster.joinSeedNodes(seedNodes)
        awaitMembersUp(3)
      }
      enterBarrier("started")

      // shutdown seed1System
      runOn(seed1) {
        seed1System.shutdown()
        seed1System.awaitTermination(remaining)
      }
      runOn(seed2, seed3) {
        awaitMembersUp(2, canNotBePartOfMemberRing = Set(seedNodes.head))
        awaitCond(clusterView.unreachableMembers.forall(_.address != seedNodes.head))
      }
      enterBarrier("seed1-shutdown")

      // then start restartedSeed1System, which has the same address as seed1System
      runOn(seed1) {
        Cluster(restartedSeed1System).joinSeedNodes(seedNodes)
        awaitCond(Cluster(restartedSeed1System).readView.members.size == 3)
        awaitCond(Cluster(restartedSeed1System).readView.members.forall(_.status == Up))
      }
      runOn(seed2, seed3) {
        awaitMembersUp(3)
      }
      enterBarrier("seed1-restarted")

    }

  }
}
