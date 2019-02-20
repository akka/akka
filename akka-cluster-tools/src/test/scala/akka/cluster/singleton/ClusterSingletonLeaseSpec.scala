package akka.cluster.singleton

import akka.actor.{Actor, ActorRef, ExtendedActorSystem, PoisonPill, Props}
import akka.cluster.{Cluster, MemberStatus, TestLease, TestLeaseExt}
import akka.lease.scaladsl.LeaseProvider
import akka.lease.{LeaseSettings, TimeoutSettings}
import akka.testkit.{AkkaSpec, TestProbe}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Success

object ImportantSingleton {

}

class ImportantSingleton(probe: ActorRef) extends Actor {

  override def preStart(): Unit =
    probe ! "preStart"

  override def postStop(): Unit =
    probe ! "postStop"

  override def receive: Receive = {
    case msg =>
      sender() ! msg
  }
}

class ClusterSingletonLeaseSpec extends AkkaSpec(
  """
     akka.loglevel = INFO
     akka.actor.provider = cluster
     test-lease {
         lease-class = akka.cluster.TestLease
         heartbeat-interval = 1s
         heartbeat-timeout = 120s
         lease-operation-timeout = 3s
     }

     akka.cluster.singleton {
       lease-implementation = "test-lease"
     }
  """.stripMargin) {

  val cluster = Cluster(system)
  val testLeaseExt = TestLeaseExt(system)


  override protected def atStartup(): Unit = {
    cluster.join(cluster.selfUniqueAddress.address)
    awaitAssert {
      cluster.selfMember.status shouldEqual MemberStatus.Up
    }
  }

 def extSystem: ExtendedActorSystem = system.asInstanceOf[ExtendedActorSystem]


  "A singleton with lease" should {

    // TODO validate correct lease name/ownwer

    "not start until lease is available" in {
      val probe = TestProbe()
      system.actorOf(ClusterSingletonManager.props(Props(new ImportantSingleton(probe.ref)), PoisonPill, ClusterSingletonManagerSettings(system)))
      val testLease = awaitAssert { testLeaseExt.getTestLease() } // allow singleton manager to create the lease
      probe.expectNoMessage()
      testLease.initialPromise.complete(Success(true))
      probe.expectMsg("preStart")
    }

    "do not start if lease acquire fails" in {
      val probe = TestProbe()
      system.actorOf(ClusterSingletonManager.props(Props(new ImportantSingleton(probe.ref)), PoisonPill, ClusterSingletonManagerSettings(system)))
      val testLease = awaitAssert { testLeaseExt.getTestLease() } // allow singleton manager to create the lease
      probe.expectNoMessage()
      testLease.initialPromise.complete(Success(false))
      probe.expectNoMessage()
    }

  }
}
