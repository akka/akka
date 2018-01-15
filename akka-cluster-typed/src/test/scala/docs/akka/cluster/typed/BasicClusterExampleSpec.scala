package docs.akka.cluster.typed

import akka.testkit.SocketUtil
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }
//#cluster-imports
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.cluster.typed._
//#cluster-imports
import akka.testkit.typed.scaladsl.TestProbe
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.duration._

object BasicClusterExampleSpec {
  val configSystem1 = ConfigFactory.parseString(
    s"""
#config-seeds
akka {
  actor {
    provider = "cluster"
  }
  remote {
    netty.tcp {
      hostname = "127.0.0.1"
      port = 2551
    }
  }

  cluster {
    seed-nodes = [
      "akka.tcp://ClusterSystem@127.0.0.1:2551",
      "akka.tcp://ClusterSystem@127.0.0.1:2552"]
  }
}
#config-seeds
     """)

  val configSystem2 = ConfigFactory.parseString(
    s"""
        akka.remote.netty.tcp.port = 0
     """
  ).withFallback(configSystem1)
}

class BasicClusterConfigSpec extends WordSpec with ScalaFutures with Eventually {

  import BasicClusterExampleSpec._

  "Cluster API" must {
    "init cluster" in {
      // config is pulled into docs, but we don't want to hardcode ports because that makes for brittle tests
      val sys1Port = SocketUtil.temporaryLocalPort()
      val sys2Port = SocketUtil.temporaryLocalPort()
      def config(port: Int) = ConfigFactory.parseString(s"""
          akka.remote.netty.tcp.port = $port
          akka.cluster.seed-nodes = [ "akka.tcp://ClusterSystem@127.0.0.1:$sys1Port", "akka.tcp://ClusterSystem@127.0.0.1:$sys2Port" ]
        """)

      val system1 = ActorSystem[Nothing](Actor.empty, "ClusterSystem", config(sys1Port).withFallback(configSystem1))
      val system2 = ActorSystem[Nothing](Actor.empty, "ClusterSystem", config(sys2Port).withFallback(configSystem2))
      try {
        val cluster1 = Cluster(system1)
        val cluster2 = Cluster(system2)
      } finally {
        system1.terminate().futureValue
        system2.terminate().futureValue
      }

    }
  }
}

object BasicClusterManualSpec {
  val clusterConfig = ConfigFactory.parseString(
    s"""
#config
akka {
  actor.provider = "cluster"
  remote {
    netty.tcp {
      hostname = "127.0.0.1"
      port = 2551
    }
  }
}
#config
     """)

  val noPort = ConfigFactory.parseString("akka.remote.netty.tcp.port = 0")

}

class BasicClusterManualSpec extends WordSpec with ScalaFutures with Eventually with Matchers {

  import BasicClusterManualSpec._

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(100, Millis)))

  "Cluster API" must {
    "init cluster" in {

      val system = ActorSystem[Nothing](Actor.empty, "ClusterSystem", noPort.withFallback(clusterConfig))
      val system2 = ActorSystem[Nothing](Actor.empty, "ClusterSystem", noPort.withFallback(clusterConfig))

      try {
        //#cluster-create
        val cluster1 = Cluster(system)
        //#cluster-create
        val cluster2 = Cluster(system2)

        //#cluster-join
        cluster1.manager ! Join(cluster1.selfMember.address)
        //#cluster-join
        cluster2.manager ! Join(cluster1.selfMember.address)

        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
          cluster2.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
        }

        //#cluster-leave
        cluster2.manager ! Leave(cluster2.selfMember.address)
        //#cluster-leave

        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up)
          cluster2.isTerminated shouldEqual true
        }
      } finally {
        system.terminate().futureValue
        system2.terminate().futureValue
      }
    }

    "subscribe to cluster events" in {
      implicit val system1 = ActorSystem[Nothing](Actor.empty, "ClusterSystem", noPort.withFallback(clusterConfig))
      val system2 = ActorSystem[Nothing](Actor.empty, "ClusterSystem", noPort.withFallback(clusterConfig))
      val system3 = ActorSystem[Nothing](Actor.empty, "ClusterSystem", noPort.withFallback(clusterConfig))

      try {
        val cluster1 = Cluster(system1)
        val cluster2 = Cluster(system2)
        val cluster3 = Cluster(system3)

        //#cluster-subscribe
        val testProbe = TestProbe[MemberEvent]()
        cluster1.subscriptions ! Subscribe(testProbe.ref, classOf[MemberEvent])
        //#cluster-subscribe

        cluster1.manager ! Join(cluster1.selfMember.address)
        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up)
        }
        testProbe.expectMsg(MemberUp(cluster1.selfMember))

        cluster2.manager ! Join(cluster1.selfMember.address)
        testProbe.expectMsgType[MemberJoined].member.address shouldEqual cluster2.selfMember.address
        testProbe.expectMsgType[MemberUp].member.address shouldEqual cluster2.selfMember.address
        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
          cluster2.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
        }

        cluster3.manager ! Join(cluster1.selfMember.address)
        testProbe.expectMsgType[MemberJoined].member.address shouldEqual cluster3.selfMember.address
        testProbe.expectMsgType[MemberUp].member.address shouldEqual cluster3.selfMember.address
        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up, MemberStatus.up)
          cluster2.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up, MemberStatus.up)
          cluster3.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up, MemberStatus.up)
        }

        //#cluster-leave-example
        cluster1.manager ! Leave(cluster2.selfMember.address)
        testProbe.expectMsgType[MemberLeft].member.address shouldEqual cluster2.selfMember.address
        testProbe.expectMsgType[MemberExited].member.address shouldEqual cluster2.selfMember.address
        testProbe.expectMsgType[MemberRemoved].member.address shouldEqual cluster2.selfMember.address
        //#cluster-leave-example

        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
          cluster3.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
        }

        system1.log.info("Downing node 3")
        cluster1.manager ! Down(cluster3.selfMember.address)
        testProbe.expectMsgType[MemberRemoved](10.seconds).member.address shouldEqual cluster3.selfMember.address

        testProbe.expectNoMessage()
      } finally {
        system1.terminate().futureValue
        system2.terminate().futureValue
        system3.terminate().futureValue
      }
    }
  }
}
