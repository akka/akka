/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import scala.concurrent.duration._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.STMultiNodeSpec
import akka.remote.testkit.MultiNodeSpec
import akka.persistence.Persistence
import com.typesafe.config.ConfigFactory
import akka.cluster.Cluster
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberUp

object ReplicatorChaosSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.cluster.roles = ["backend"]
    akka.log-dead-letters-during-shutdown = off
    """))

  testTransport(on = true)
}

class ReplicatorChaosSpecMultiJvmNode1 extends ReplicatorChaosSpec
class ReplicatorChaosSpecMultiJvmNode2 extends ReplicatorChaosSpec
class ReplicatorChaosSpecMultiJvmNode3 extends ReplicatorChaosSpec
class ReplicatorChaosSpecMultiJvmNode4 extends ReplicatorChaosSpec
class ReplicatorChaosSpecMultiJvmNode5 extends ReplicatorChaosSpec

class ReplicatorChaosSpec extends MultiNodeSpec(ReplicatorChaosSpec) with STMultiNodeSpec with ImplicitSender {
  import ReplicatorChaosSpec._
  import Replicator._

  override def initialParticipants = roles.size

  implicit val cluster = Cluster(system)
  val replicator = system.actorOf(Replicator.props(role = Some("backend"), gossipInterval = 1.second), "replicator")
  val timeout = 3.seconds.dilated

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  def assertValue(key: String, expected: Any): Unit =
    within(10.seconds) {
      awaitAssert {
        replicator ! Get(key)
        val value = expectMsgPF() {
          case GetResult(`key`, c: GCounter, _, _)  ⇒ c.value
          case GetResult(`key`, c: PNCounter, _, _) ⇒ c.value
          case GetResult(`key`, c: GSet, _, _)      ⇒ c.value
          case GetResult(`key`, c: ORSet, _, _)     ⇒ c.value
        }
        value should be(expected)
      }
    }

  def assertDeleted(key: String): Unit =
    within(5.seconds) {
      awaitAssert {
        replicator ! Get(key, ReadOne, timeout)
        expectMsg(DataDeleted(key))
      }
    }

  "Replicator in chaotic cluster" must {

    "replicate data in initial phase" in {
      join(first, first)
      join(second, first)
      join(third, first)
      join(fourth, first)
      join(fifth, first)

      within(10.seconds) {
        awaitAssert {
          replicator ! Internal.GetNodeCount
          expectMsg(Internal.NodeCount(5))
        }
      }

      runOn(first) {
        var c = GCounter()
        var pn = PNCounter()
        (0 until 5) foreach { i ⇒
          c :+= 1
          pn :-= 1
          replicator ! Update("A", c, i)
          replicator ! Update("B", pn, i)
          replicator ! Update("C", c, i, WriteAll, timeout)
        }
        receiveN(15).map(_.getClass).toSet should be(Set(classOf[UpdateSuccess]))
      }

      runOn(second) {
        val c = GCounter() :+ 20
        val pn = PNCounter() :+ 20
        replicator ! Update("A", c, 0)
        replicator ! Update("B", pn, 0, WriteTwo, timeout)
        replicator ! Update("C", c, 0, WriteAll, timeout)
        receiveN(3).toSet should be(Set(UpdateSuccess("A", 0, None),
          UpdateSuccess("B", 0, None), UpdateSuccess("C", 0, None)))

        replicator ! Update("E", GSet() :+ "e1" :+ "e2", 0)
        expectMsg(UpdateSuccess("E", 0, None))

        replicator ! Update("F", ORSet() :+ "e1" :+ "e2", 0)
        expectMsg(UpdateSuccess("F", 0, None))
      }

      runOn(fourth) {
        val c = GCounter() :+ 40
        replicator ! Update("D", c, 0)
        expectMsg(UpdateSuccess("D", 0, None))

        replicator ! Update("E", GSet() :+ "e2" :+ "e3", 0)
        expectMsg(UpdateSuccess("E", 0, None))

        replicator ! Update("F", ORSet() :+ "e2" :+ "e3", 0)
        expectMsg(UpdateSuccess("F", 0, None))
      }

      runOn(fifth) {
        val c = GCounter() :+ 50
        replicator ! Update("X", c, 0, WriteTwo, timeout)
        expectMsg(UpdateSuccess("X", 0, None))
        replicator ! Delete("X")
        expectMsg(DeleteSuccess("X"))
      }

      enterBarrier("initial-updates-done")

      assertValue("A", 25)
      assertValue("B", 15)
      assertValue("C", 25)
      assertValue("E", Set("e1", "e2", "e3"))
      assertValue("F", Set("e1", "e2", "e3"))
      assertDeleted("X")

      enterBarrier("after-1")
    }

    "be available during network split" in {
      val side1 = Seq(first, second)
      val side2 = Seq(third, fourth, fifth)
      runOn(first) {
        for (a ← side1; b ← side2)
          testConductor.blackhole(a, b, Direction.Both).await
      }
      enterBarrier("split")

      runOn(first) {
        replicator ! Get("A")
        val GetResult("A", c: GCounter, seqNo, _) = expectMsgType[GetResult]
        replicator ! Update("A", c :+ 1, seqNo, WriteTwo, timeout)
        expectMsg(UpdateSuccess("A", seqNo, None))
      }

      runOn(third) {
        replicator ! Get("A")
        val GetResult("A", c: GCounter, seqNo, _) = expectMsgType[GetResult]
        replicator ! Update("A", c :+ 2, seqNo, WriteTwo, timeout)
        expectMsg(UpdateSuccess("A", seqNo, None))

        replicator ! Get("E")
        val GetResult("E", sE: GSet, seqNo2, _) = expectMsgType[GetResult]
        replicator ! Update("E", sE :+ "e4", seqNo2, WriteTwo, timeout)
        expectMsg(UpdateSuccess("E", seqNo, None))

        replicator ! Get("F")
        val GetResult("F", sF: ORSet, seqNo3, _) = expectMsgType[GetResult]
        replicator ! Update("F", sF :- "e2", seqNo3, WriteTwo, timeout)
        expectMsg(UpdateSuccess("F", seqNo, None))
      }
      runOn(fourth) {
        replicator ! Get("D")
        val GetResult("D", c: GCounter, seqNo, _) = expectMsgType[GetResult]
        replicator ! Update("D", c :+ 1, seqNo, WriteTwo, timeout)
        expectMsg(UpdateSuccess("D", seqNo, None))
      }
      enterBarrier("update-during-split")

      runOn(side1: _*) {
        assertValue("A", 26)
        assertValue("B", 15)
        assertValue("D", 40)
        assertValue("E", Set("e1", "e2", "e3"))
        assertValue("F", Set("e1", "e2", "e3"))
      }
      runOn(side2: _*) {
        assertValue("A", 27)
        assertValue("B", 15)
        assertValue("D", 41)
        assertValue("E", Set("e1", "e2", "e3", "e4"))
        assertValue("F", Set("e1", "e3"))
      }
      enterBarrier("update-during-split-verified")

      runOn(first) {
        testConductor.exit(fourth, 0).await
      }

      enterBarrier("after-2")
    }

    "converge after partition" in {
      val side1 = Seq(first, second)
      val side2 = Seq(third, fifth) // fourth was shutdown
      runOn(first) {
        for (a ← side1; b ← side2)
          testConductor.passThrough(a, b, Direction.Both).await
      }
      enterBarrier("split-repaired")

      assertValue("A", 28)
      assertValue("B", 15)
      assertValue("C", 25)
      assertValue("D", 41)
      assertValue("E", Set("e1", "e2", "e3", "e4"))
      assertValue("F", Set("e1", "e3"))
      assertDeleted("X")

      enterBarrier("after-3")
    }
  }

}

