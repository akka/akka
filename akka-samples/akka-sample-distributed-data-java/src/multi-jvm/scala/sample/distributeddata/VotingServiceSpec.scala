package sample.distributeddata

import java.math.BigInteger
import scala.concurrent.duration._
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.Replicator.GetReplicaCount
import akka.cluster.ddata.Replicator.ReplicaCount
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object VotingServiceSpec extends MultiNodeConfig {
  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.log-dead-letters-during-shutdown = off
    """))

}

class VotingServiceSpecMultiJvmNode1 extends VotingServiceSpec
class VotingServiceSpecMultiJvmNode2 extends VotingServiceSpec
class VotingServiceSpecMultiJvmNode3 extends VotingServiceSpec

class VotingServiceSpec extends MultiNodeSpec(VotingServiceSpec) with STMultiNodeSpec with ImplicitSender {
  import VotingServiceSpec._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Demo of a replicated voting" must {

    "join cluster" in within(20.seconds) {
      join(node1, node1)
      join(node2, node1)
      join(node3, node1)

      awaitAssert {
        DistributedData(system).replicator ! GetReplicaCount
        expectMsg(ReplicaCount(roles.size))
      }
      enterBarrier("after-1")
    }

    "count votes correctly" in within(15.seconds) {
      import VotingService._
      val votingService = system.actorOf(Props[VotingService], "votingService")
      val N = 1000
      runOn(node1) {
        votingService ! VotingService.OPEN
        for (n ← 1 to N) {
          votingService ! new Vote("#" + ((n % 20) + 1))
        }
      }
      runOn(node2, node3) {
        // wait for it to open
        val p = TestProbe()
        awaitAssert {
          votingService.tell(VotingService.GET_VOTES, p.ref)
          p.expectMsgType[Votes](3.seconds).open should be(true)
        }
        for (n ← 1 to N) {
          votingService ! new Vote("#" + ((n % 20) + 1))
        }
      }
      enterBarrier("voting-done")
      runOn(node3) {
        votingService ! VotingService.CLOSE
      }

      val expected = (1 to 20).map(n => "#" + n -> BigInteger.valueOf(3L * N / 20)).toMap
      awaitAssert {
        votingService ! VotingService.GET_VOTES
        val votes = expectMsgType[Votes](3.seconds)
        votes.open should be (false)
        import scala.collection.JavaConverters._
        votes.result.asScala.toMap should be (expected)
      }

      enterBarrier("after-2")
    }
  }

}

