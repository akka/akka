package sample.cluster.stats

import language.postfixOps
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

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

object StatsSampleSingleMasterSpecConfig extends MultiNodeConfig {
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
    # don't use sigar for tests, native lib not in path
    akka.cluster.metrics.collector-class = akka.cluster.JmxMetricsCollector
    #//#router-deploy-config
    akka.actor.deployment {
      /statsFacade/statsService/workerRouter {
          router = consistent-hashing
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
class StatsSampleSingleMasterSpecMultiJvmNode1 extends StatsSampleSingleMasterSpec
class StatsSampleSingleMasterSpecMultiJvmNode2 extends StatsSampleSingleMasterSpec
class StatsSampleSingleMasterSpecMultiJvmNode3 extends StatsSampleSingleMasterSpec

abstract class StatsSampleSingleMasterSpec extends MultiNodeSpec(StatsSampleSingleMasterSpecConfig)
  with WordSpec with MustMatchers with BeforeAndAfterAll with ImplicitSender {

  import StatsSampleSingleMasterSpecConfig._

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

  "The stats sample with single master" must {
    "illustrate how to startup cluster" in within(15 seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      Cluster(system) join node(first).address

      expectMsgAllOf(
        MemberUp(Member(node(first).address, MemberStatus.Up)),
        MemberUp(Member(node(second).address, MemberStatus.Up)),
        MemberUp(Member(node(third).address, MemberStatus.Up)))

      Cluster(system).unsubscribe(testActor)
      
      system.actorOf(Props[StatsFacade], "statsFacade")

      testConductor.enter("all-up")
    }

    "show usage of the statsFacade" in within(20 seconds) {
      val facade = system.actorFor(RootActorPath(node(third).address) / "user" / "statsFacade")

      // eventually the service should be ok,
      // service and worker nodes might not be up yet
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