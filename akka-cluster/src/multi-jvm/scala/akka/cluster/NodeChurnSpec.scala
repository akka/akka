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

object NodeChurnMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster.auto-down-unreachable-after = 1s
      akka.remote.log-frame-size-exceeding = 2000b
      """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

  class LogListener(testActor: ActorRef) extends Actor {
    def receive = {
      case Info(_, _, msg: String) if msg.startsWith("New maximum payload size for [akka.cluster.GossipEnvelope]") ⇒
        testActor ! msg
      case _ ⇒
    }
  }
}

class NodeChurnMultiJvmNode1 extends NodeChurnSpec
class NodeChurnMultiJvmNode2 extends NodeChurnSpec
class NodeChurnMultiJvmNode3 extends NodeChurnSpec

abstract class NodeChurnSpec
  extends MultiNodeSpec(NodeChurnMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender {

  import NodeChurnMultiJvmSpec._

  def seedNodes: immutable.IndexedSeq[Address] = Vector(first, second, third)

  override def afterAll(): Unit = {
    super.afterAll()
  }

  val rounds = 3

  override def expectedTestDuration: FiniteDuration = 45.seconds * rounds

  def awaitAllMembersUp(additionaSystems: Vector[ActorSystem]): Unit = {
    val numberOfMembers = roles.size + roles.size * additionaSystems.size
    awaitMembersUp(numberOfMembers)
    within(20.seconds) {
      awaitAssert {
        additionaSystems.foreach { s ⇒
          val c = Cluster(s)
          c.state.members.size should be(numberOfMembers)
          c.state.members.forall(_.status == MemberStatus.Up) shouldBe true
        }
      }
    }
  }

  def awaitRemoved(additionaSystems: Vector[ActorSystem], round: Int): Unit = {
    awaitMembersUp(roles.size, timeout = 40.seconds)
    enterBarrier("removed-" + round)
    within(3.seconds) {
      awaitAssert {
        additionaSystems.foreach { s ⇒
          withClue(s"Cluster(s).self:") {
            Cluster(s).isTerminated should be(true)
          }
        }
      }
    }
  }

  "Cluster with short lived members" must {
    "setup stable nodes" taggedAs LongRunningTest in within(15.seconds) {
      val logListener = system.actorOf(Props(classOf[LogListener], testActor), "logListener")
      system.eventStream.subscribe(logListener, classOf[Info])
      cluster.joinSeedNodes(seedNodes)
      awaitMembersUp(roles.size)
      enterBarrier("stable")
    }

    "join and remove transient nodes without growing gossip payload" taggedAs LongRunningTest in {
      // This test is configured with log-frame-size-exceeding and the LogListener
      // will send to the testActor if unexpected increase in message payload size.
      // It will fail after a while if vector clock entries of removed nodes are not pruned.
      for (n ← 1 to rounds) {
        log.info("round-" + n)
        val systems = Vector.fill(5)(ActorSystem(system.name, system.settings.config))
        systems.foreach { s ⇒
          muteDeadLetters()(s)
          Cluster(s).joinSeedNodes(seedNodes)
        }
        awaitAllMembersUp(systems)
        enterBarrier("members-up-" + n)
        systems.foreach { node ⇒
          if (n % 2 == 0)
            Cluster(node).down(Cluster(node).selfAddress)
          else
            Cluster(node).leave(Cluster(node).selfAddress)
        }
        awaitRemoved(systems, n)
        enterBarrier("members-removed-" + n)
        systems.foreach(_.terminate().await)
        log.info("end of round-" + n)
        // log listener will send to testActor if payload size exceed configured log-frame-size-exceeding
        expectNoMsg(2.seconds)
      }
      expectNoMsg(5.seconds)
    }

  }
}
