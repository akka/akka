/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.singleton

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Status.Failure
import akka.actor.{ Actor, ActorRef, ExtendedActorSystem, PoisonPill, Props }
import akka.cluster.TestLease.AcquireReq
import akka.cluster.{ Cluster, MemberStatus, TestLease, TestLeaseExt }
import akka.lease.scaladsl.LeaseProvider
import akka.lease.{ LeaseSettings, TimeoutSettings }
import akka.testkit.{ AkkaSpec, TestProbe }
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
    case msg ⇒
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
       lease-retry-interval = 100ms
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

  val counter = new AtomicInteger()

  def nextName() = s"important-${counter.getAndIncrement()}"

  val shortDuration = 50.millis

  val leaseOwner = cluster.selfMember.address.hostPort

  "A singleton with lease" should {

    "not start until lease is available" in {
      val probe = TestProbe()
      val name = nextName()
      system.actorOf(ClusterSingletonManager.props(Props(new ImportantSingleton(probe.ref)), PoisonPill, ClusterSingletonManagerSettings(system)), name)
      val testLease = awaitAssert {
        testLeaseExt.getTestLease(s"singleton-$name")
      } // allow singleton manager to create the lease
      probe.expectNoMessage(shortDuration)
      testLease.initialPromise.complete(Success(true))
      probe.expectMsg("preStart")
    }

    "do not start if lease acquire returns false" in {
      val probe = TestProbe()
      val name = nextName()
      system.actorOf(ClusterSingletonManager.props(Props(new ImportantSingleton(probe.ref)), PoisonPill, ClusterSingletonManagerSettings(system)), name)
      val testLease = awaitAssert {
        testLeaseExt.getTestLease(s"singleton-$name")
      } // allow singleton manager to create the lease
      probe.expectNoMessage(shortDuration)
      testLease.initialPromise.complete(Success(false))
      probe.expectNoMessage(shortDuration)
    }

    "retry trying to get lease if acquire returns false" in {
      val singletonProbe = TestProbe()
      val name = nextName()
      system.actorOf(ClusterSingletonManager.props(Props(new ImportantSingleton(singletonProbe.ref)), PoisonPill, ClusterSingletonManagerSettings(system)), name)
      val testLease = awaitAssert {
        testLeaseExt.getTestLease(s"singleton-$name")
      } // allow singleton manager to create the lease
      testLease.probe.expectMsg(AcquireReq(leaseOwner))
      singletonProbe.expectNoMessage(shortDuration)
      val nextResponse = Promise[Boolean]
      testLease.setNextAcquireResult(nextResponse.future)
      testLease.initialPromise.complete(Success(false))
      testLease.probe.expectMsg(AcquireReq(leaseOwner))
      singletonProbe.expectNoMessage(shortDuration)
      nextResponse.complete(Success(true))
      singletonProbe.expectMsg("preStart")
    }

    "do not start if lease acquire fails" in {
      val probe = TestProbe()
      val name = nextName()
      system.actorOf(ClusterSingletonManager.props(Props(new ImportantSingleton(probe.ref)), PoisonPill, ClusterSingletonManagerSettings(system)), name)
      val testLease = awaitAssert {
        testLeaseExt.getTestLease(s"singleton-$name")
      } // allow singleton manager to create the lease
      probe.expectNoMessage(shortDuration)
      testLease.initialPromise.failure(new RuntimeException("no lease for you"))
      probe.expectNoMessage(shortDuration)
    }

    "retry trying to get lease if acquire returns fails" in {
      val singletonProbe = TestProbe()
      val name = nextName()
      system.actorOf(ClusterSingletonManager.props(Props(new ImportantSingleton(singletonProbe.ref)), PoisonPill, ClusterSingletonManagerSettings(system)), name)
      val testLease = awaitAssert {
        testLeaseExt.getTestLease(s"singleton-$name")
      } // allow singleton manager to create the lease
      testLease.probe.expectMsg(AcquireReq(leaseOwner))
      singletonProbe.expectNoMessage(shortDuration)
      val nextResponse = Promise[Boolean]
      testLease.setNextAcquireResult(nextResponse.future)
      testLease.initialPromise.failure(new RuntimeException("no lease for you"))
      testLease.probe.expectMsg(AcquireReq(leaseOwner))
      singletonProbe.expectNoMessage(shortDuration)
      nextResponse.complete(Success(true))
      singletonProbe.expectMsg("preStart")
    }
  }
}
