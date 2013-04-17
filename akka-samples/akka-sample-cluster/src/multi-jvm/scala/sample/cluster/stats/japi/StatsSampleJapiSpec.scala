package sample.cluster.stats.japi

import language.postfixOps
import scala.concurrent.duration._

import akka.actor.Props
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberUp
import sample.cluster.stats.japi.StatsMessages._
import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender

object StatsSampleJapiSpecConfig extends MultiNodeConfig {
  // register the named roles (nodes) of the test
  val first = role("first")
  val second = role("second")
  val third = role("thrid")

  // this configuration will be used for all nodes
  // note that no fixed host names and ports are used
  commonConfig(ConfigFactory.parseString("""
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.roles = [compute]
    akka.cluster.auto-join = off
    # don't use sigar for tests, native lib not in path
    akka.cluster.metrics.collector-class = akka.cluster.JmxMetricsCollector
    akka.actor.deployment {
      /statsService/workerRouter {
          router = consistent-hashing
          nr-of-instances = 100
          cluster {
            enabled = on
            routees-path = "/user/statsWorker"
            allow-local-routees = on
            use-role = compute
          }
        }
    }
    """))

}

// need one concrete test class per node
class StatsSampleJapiSpecMultiJvmNode1 extends StatsSampleJapiSpec
class StatsSampleJapiSpecMultiJvmNode2 extends StatsSampleJapiSpec
class StatsSampleJapiSpecMultiJvmNode3 extends StatsSampleJapiSpec

abstract class StatsSampleJapiSpec extends MultiNodeSpec(StatsSampleJapiSpecConfig)
  with WordSpec with MustMatchers with BeforeAndAfterAll
  with ImplicitSender {

  import StatsSampleJapiSpecConfig._

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

  "The japi stats sample" must {

    "illustrate how to startup cluster" in within(15 seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      val firstAddress = node(first).address
      val secondAddress = node(second).address
      val thirdAddress = node(third).address

      Cluster(system) join firstAddress

      system.actorOf(Props[StatsWorker], "statsWorker")
      system.actorOf(Props[StatsService], "statsService")

      receiveN(3).collect { case MemberUp(m) => m.address }.toSet must be (
          Set(firstAddress, secondAddress, thirdAddress))

      Cluster(system).unsubscribe(testActor)

      testConductor.enter("all-up")
    }

    "show usage of the statsService from one node" in within(15 seconds) {
      runOn(second) {
        assertServiceOk()
      }

      testConductor.enter("done-2")
    }

    def assertServiceOk(): Unit = {
      val service = system.actorSelection(node(third) / "user" / "statsService")
      // eventually the service should be ok,
      // first attempts might fail because worker actors not started yet
      awaitAssert {
        service ! new StatsJob("this is the text that will be analyzed")
        expectMsgType[StatsResult](1.second).getMeanWordLength must be(3.875 plusOrMinus 0.001)
      }
    }
    //#test-statsService

    "show usage of the statsService from all nodes" in within(15 seconds) {
      assertServiceOk()

      testConductor.enter("done-3")
    }

  }

}