package sample.cluster.stats

import language.postfixOps
import scala.concurrent.util.duration._

import com.typesafe.config.ConfigFactory

import StatsSampleSpec.first
import StatsSampleSpec.third
import akka.actor.Props
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberUp
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender

object StatsSampleSingleMasterSpec extends MultiNodeConfig {
  // register the named roles (nodes) of the test
  val first = role("first")
  val second = role("second")
  val third = role("thrid")

  // this configuration will be used for all nodes
  // note that no fixed host names and ports are used
  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-join = off
    #//#router-deploy-config
    akka.actor.deployment {
      /statsFacade/statsService/workerRouter {
          # FIXME use consistent hashing instead
          router = round-robin
          nr-of-instances = 100
          cluster {
            enabled = on
            max-nr-of-instances-per-node = 3
            allow-local-routees = off
          }
        }
    }
    #//#router-deploy-config
    """))

}

// need one concrete test class per node
class StatsSampleSingleMasterMultiJvmNode1 extends StatsSampleSingleMasterSpec
class StatsSampleSingleMasterMultiJvmNode2 extends StatsSampleSingleMasterSpec
class StatsSampleSingleMasterMultiJvmNode3 extends StatsSampleSingleMasterSpec

abstract class StatsSampleSingleMasterSpec extends MultiNodeSpec(StatsSampleSingleMasterSpec)
  with ImplicitSender {

  import StatsSampleSpec._

  override def initialParticipants = roles.size

  "The stats sample with single master" must {
    "illustrate how to startup cluster" in within(10 seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      Cluster(system) join node(first).address
      system.actorOf(Props[StatsFacade], "statsFacade")

      expectMsgAllOf(
        MemberUp(Member(node(first).address, MemberStatus.Up)),
        MemberUp(Member(node(second).address, MemberStatus.Up)),
        MemberUp(Member(node(third).address, MemberStatus.Up)))

      Cluster(system).unsubscribe(testActor)

      testConductor.enter("all-up")
    }

    "show usage of the statsFacade" in within(5 seconds) {
      val facade = system.actorFor(RootActorPath(node(third).address) / "user" / "statsFacade")

      // eventually the service should be ok,
      // worker nodes might not be up yet
      awaitCond {
        facade ! StatsJob("this is the text that will be analyzed")
        expectMsgPF() {
          case unavailble: JobFailed ⇒ false
          case StatsResult(meanWordLength) ⇒
            meanWordLength must be(3.875 plusOrMinus 0.001)
            true
        }
      }

      testConductor.enter("done")
    }
  }

}