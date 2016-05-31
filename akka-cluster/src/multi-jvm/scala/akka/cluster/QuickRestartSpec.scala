/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import scala.collection.immutable
import scala.language.postfixOps
import scala.concurrent.duration._
import akka.actor.Address
import akka.cluster.MemberStatus._
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.event.Logging.Info
import akka.actor.Actor
import akka.actor.Props

object QuickRestartMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster.auto-down-unreachable-after = off
      """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

}

class QuickRestartMultiJvmNode1 extends QuickRestartSpec
class QuickRestartMultiJvmNode2 extends QuickRestartSpec
class QuickRestartMultiJvmNode3 extends QuickRestartSpec

abstract class QuickRestartSpec
  extends MultiNodeSpec(QuickRestartMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender {

  import QuickRestartMultiJvmSpec._

  def seedNodes: immutable.IndexedSeq[Address] = Vector(first, second, third)

  val rounds = 20

  override def expectedTestDuration: FiniteDuration = 45.seconds * rounds

  "Quickly restarting node" must {
    "setup stable nodes" taggedAs LongRunningTest in within(15.seconds) {
      cluster.joinSeedNodes(seedNodes)
      awaitMembersUp(roles.size)
      enterBarrier("stable")
    }

    "join and restart" taggedAs LongRunningTest in {
      val totalNumberOfNodes = roles.size + 1
      var restartingSystem: ActorSystem = null // only used on second
      for (n ← 1 to rounds) {
        log.info("round-" + n)
        runOn(second) {
          restartingSystem =
            if (restartingSystem == null)
              ActorSystem(system.name,
                ConfigFactory.parseString(s"akka.cluster.roles = [round-$n]")
                  .withFallback(system.settings.config))
            else
              ActorSystem(system.name,
                ConfigFactory.parseString(s"""
                  akka.cluster.roles = [round-$n]
                  akka.remote.netty.tcp.port = ${Cluster(restartingSystem).selfAddress.port.get}""") // same port
                  .withFallback(system.settings.config))
          log.info("Restarting node has address: {}", Cluster(restartingSystem).selfUniqueAddress)
          Cluster(restartingSystem).joinSeedNodes(seedNodes)
          within(20.seconds) {
            awaitAssert {
              Cluster(restartingSystem).state.members.size should ===(totalNumberOfNodes)
              Cluster(restartingSystem).state.members.map(_.status == MemberStatus.Up)
            }
          }
        }

        enterBarrier("joined-" + n)
        within(20.seconds) {
          awaitAssert {
            Cluster(system).state.members.size should ===(totalNumberOfNodes)
            Cluster(system).state.members.map(_.status == MemberStatus.Up)
            // use the role to test that it is the new incarnation that joined, sneaky
            Cluster(system).state.members.flatMap(_.roles) should ===(Set(s"round-$n"))
          }
        }
        enterBarrier("members-up-" + n)

        runOn(second) {
          restartingSystem.terminate().await
        }
        // don't wait for it to be removed, new incarnation will join in next round
        enterBarrier("terminated-" + n)
        log.info("end of round-" + n)
      }
    }

  }
}
