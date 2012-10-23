package sample.cluster.stats

import language.postfixOps
import scala.concurrent.duration._

import akka.actor.Props
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberUp

//#MultiNodeConfig
import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory

object StatsSampleSpecConfig extends MultiNodeConfig {
  // register the named roles (nodes) of the test
  val first = role("first")
  val second = role("second")
  val third = role("thrid")

  // this configuration will be used for all nodes
  // note that no fixed host names and ports are used
  commonConfig(ConfigFactory.parseString("""
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-join = off
    #//#router-lookup-config
    akka.actor.deployment {
      /statsService/workerRouter {
          router = consistent-hashing
          nr-of-instances = 100
          cluster {
            enabled = on
            routees-path = "/user/statsWorker"
            allow-local-routees = on
          }
        }
    }
    #//#router-lookup-config
    """))

}
//#MultiNodeConfig

//#concrete-tests
// need one concrete test class per node
class StatsSampleSpecMultiJvmNode1 extends StatsSampleSpec
class StatsSampleSpecMultiJvmNode2 extends StatsSampleSpec
class StatsSampleSpecMultiJvmNode3 extends StatsSampleSpec
//#concrete-tests

//#abstract-test
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender

abstract class StatsSampleSpec extends MultiNodeSpec(StatsSampleSpecConfig)
  with WordSpec with MustMatchers with BeforeAndAfterAll
  with ImplicitSender {

  import StatsSampleSpecConfig._

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

//#abstract-test  

  "The stats sample" must {

    //#startup-cluster
    "illustrate how to startup cluster" in within(10 seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      //#addresses 
      val firstAddress = node(first).address
      val secondAddress = node(second).address
      val thirdAddress = node(third).address
      //#addresses

      //#join
      Cluster(system) join firstAddress
      //#join

      system.actorOf(Props[StatsWorker], "statsWorker")
      system.actorOf(Props[StatsService], "statsService")

      expectMsgAllOf(
        MemberUp(Member(firstAddress, MemberStatus.Up)),
        MemberUp(Member(secondAddress, MemberStatus.Up)),
        MemberUp(Member(thirdAddress, MemberStatus.Up)))

      Cluster(system).unsubscribe(testActor)

      testConductor.enter("all-up")
    }
    //#startup-cluster


    //#test-statsService
    "show usage of the statsService from one node" in within(5 seconds) {
      runOn(second) {
        val service = system.actorFor("/user/statsService")
        service ! StatsJob("this is the text that will be analyzed")
        val meanWordLength = expectMsgPF() {
          case StatsResult(meanWordLength) ⇒ meanWordLength
        }
        meanWordLength must be(3.875 plusOrMinus 0.001)
      }

      testConductor.enter("done-2")
    }
    //#test-statsService
    
    "show usage of the statsService from all nodes" in within(5 seconds) {
      val service = system.actorFor("/user/statsService")
      service ! StatsJob("this is the text that will be analyzed")
      val meanWordLength = expectMsgPF() {
        case StatsResult(meanWordLength) ⇒ meanWordLength
      }
      meanWordLength must be(3.875 plusOrMinus 0.001)

      testConductor.enter("done-3")
    }


  }

}