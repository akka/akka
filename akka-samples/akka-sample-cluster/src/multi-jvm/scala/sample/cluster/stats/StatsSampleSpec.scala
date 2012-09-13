package sample.cluster.stats

import language.postfixOps
import scala.concurrent.util.duration._
import com.typesafe.config.ConfigFactory

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

object StatsSampleSpec extends MultiNodeConfig {
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
          # FIXME use consistent hashing instead
          router = round-robin
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

// need one concrete test class per node
class StatsSampleMultiJvmNode1 extends StatsSampleSpec
class StatsSampleMultiJvmNode2 extends StatsSampleSpec
class StatsSampleMultiJvmNode3 extends StatsSampleSpec

abstract class StatsSampleSpec extends MultiNodeSpec(StatsSampleSpec)
  with ImplicitSender {

  import StatsSampleSpec._

  override def initialParticipants = roles.size

  "The stats sample" must {
    "illustrate how to startup cluster" in within(10 seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      Cluster(system) join node(first).address
      system.actorOf(Props[StatsWorker], "statsWorker")
      system.actorOf(Props[StatsService], "statsService")

      expectMsgAllOf(
        MemberUp(Member(node(first).address, MemberStatus.Up)),
        MemberUp(Member(node(second).address, MemberStatus.Up)),
        MemberUp(Member(node(third).address, MemberStatus.Up)))

      Cluster(system).unsubscribe(testActor)

      testConductor.enter("all-up")
    }

    "show usage of the statsService" in within(5 seconds) {

      val service = system.actorFor(RootActorPath(node(third).address) / "user" / "statsService")
      service ! StatsJob("this is the text that will be analyzed")
      val meanWordLength = expectMsgPF() {
        case StatsResult(meanWordLength) ⇒ meanWordLength
      }
      meanWordLength must be(3.875 plusOrMinus 0.001)

      testConductor.enter("done")
    }
  }

}