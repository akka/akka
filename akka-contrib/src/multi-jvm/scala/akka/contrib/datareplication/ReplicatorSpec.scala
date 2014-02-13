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

object ReplicatorSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.log-dead-letters-during-shutdown = off
    """))

  testTransport(on = true)

}

class ReplicatorSpecMultiJvmNode1 extends ReplicatorSpec
class ReplicatorSpecMultiJvmNode2 extends ReplicatorSpec
class ReplicatorSpecMultiJvmNode3 extends ReplicatorSpec

class ReplicatorSpec extends MultiNodeSpec(ReplicatorSpec) with STMultiNodeSpec with ImplicitSender {
  import ReplicatorSpec._
  import Replicator._

  override def initialParticipants = roles.size

  implicit val cluster = Cluster(system)
  val replicator = system.actorOf(Replicator.props(role = None, gossipInterval = 1.second,
    maxDeltaElements = 10), "replicator")
  val timeout = 2.seconds.dilated

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Cluster CRDT" must {

    "work in single node cluster" in {
      join(first, first)

      runOn(first) {

        within(5.seconds) {
          awaitAssert {
            replicator ! Internal.GetNodeCount
            expectMsg(Internal.NodeCount(1))
          }
        }

        val changedProbe = TestProbe()
        replicator ! Subscribe("A", changedProbe.ref)
        replicator ! Subscribe("X", changedProbe.ref)

        replicator ! Get("A", ReadOne, timeout)
        expectMsg(NotFound("A", None))

        val c3 = GCounter() :+ 3
        replicator ! Update("A", c3, 0, WriteOne, timeout)
        expectMsg(UpdateSuccess("A", 0, None))
        replicator ! Get("A", ReadOne, timeout)
        expectMsg(GetResult("A", c3, 1, None))
        changedProbe.expectMsg(Changed("A", c3))

        val c4 = c3 :+ 1
        // too strong consistency level
        replicator ! Update("A", c4, 1, WriteTwo, timeout)
        expectMsg(ReplicationFailure("A", 1, None))
        replicator ! Get("A", ReadOne, timeout)
        expectMsg(GetResult("A", c4, 2, None))
        changedProbe.expectMsg(Changed("A", c4))

        val c5 = c4 :+ 1
        // too strong consistency level
        replicator ! Update("A", c5, 2, WriteQuorum, timeout)
        expectMsg(ReplicationFailure("A", 2, None))
        replicator ! Get("A", ReadOne, timeout)
        expectMsg(GetResult("A", c5, 3, None))
        changedProbe.expectMsg(Changed("A", c5))

        val c6 = c5 :+ 1
        replicator ! Update("A", c6, 3, WriteAll, timeout)
        expectMsg(UpdateSuccess("A", 3, None))
        replicator ! Get("A", ReadAll, timeout)
        expectMsg(GetResult("A", c6, 4, None))
        changedProbe.expectMsg(Changed("A", c6))

        val c7 = c6 :+ 1
        // wrong seqNo
        replicator ! Update("A", c7, 3, WriteOne, timeout)
        expectMsg(WrongSeqNo("A", c6, 3, 4, None))
        replicator ! Get("A", ReadOne, timeout)
        expectMsg(GetResult("A", c6, 4, None))
        changedProbe.expectNoMsg(200.millis)

        val c9 = GCounter() :+ 9
        replicator ! Update("X", c9, 0, WriteOne, timeout)
        expectMsg(UpdateSuccess("X", 0, None))
        changedProbe.expectMsg(Changed("X", c9))
        replicator ! Delete("X", WriteOne, timeout)
        expectMsg(DeleteSuccess("X"))
        changedProbe.expectMsg(DataDeleted("X"))
        replicator ! Get("X", ReadOne, timeout)
        expectMsg(DataDeleted("X"))
        replicator ! Get("X", ReadAll, timeout)
        expectMsg(DataDeleted("X"))
        replicator ! Update("X", c9 :+ 1, 1, WriteOne, timeout)
        expectMsg(DataDeleted("X"))
        replicator ! Delete("X", WriteOne, timeout)
        expectMsg(DataDeleted("X"))

        replicator ! GetKeys
        expectMsg(GetKeysResult(Set("A")))
      }

      enterBarrier("after-1")
    }
  }

  "replicate values to new node" in {
    join(second, first)

    runOn(first, second) {
      within(10.seconds) {
        awaitAssert {
          replicator ! Internal.GetNodeCount
          expectMsg(Internal.NodeCount(2))
        }
      }
    }

    enterBarrier("2-nodes")

    runOn(second) {
      val changedProbe = TestProbe()
      replicator ! Subscribe("A", changedProbe.ref)
      // "A" should be replicated via gossip to the new node
      within(5.seconds) {
        awaitAssert {
          replicator ! Get("A", ReadOne, timeout)
          val c = expectMsgPF() { case GetResult("A", c: GCounter, 0, _) ⇒ c }
          c.value should be(6)
        }
      }
      val c = changedProbe.expectMsgPF() { case Changed("A", c: GCounter) ⇒ c }
      c.value should be(6)
    }

    enterBarrier("after-2")
  }

  "work in 2 node cluster" in {

    runOn(first, second) {

      // start with 20 on both nodes
      val c20 = GCounter() :+ 20
      replicator ! Update("B", c20, 0, WriteOne, timeout)
      expectMsg(UpdateSuccess("B", 0, None))

      // add 1 on both nodes using WriteTwo
      val c21 = c20 :+ 1
      replicator ! Update("B", c21, 1, WriteTwo, timeout)
      expectMsg(UpdateSuccess("B", 1, None))

      // the total, after replication should be 42
      awaitAssert {
        replicator ! Get("B", ReadTwo, timeout)
        val c = expectMsgPF() { case GetResult("B", c: GCounter, 2, _) ⇒ c }
        c.value should be(42)
      }

      // add 1 on both nodes using WriteAll
      val c22 = c21 :+ 1
      replicator ! Update("B", c22, 2, WriteAll, timeout)
      expectMsg(UpdateSuccess("B", 2, None))

      // the total, after replication should be 44
      awaitAssert {
        replicator ! Get("B", ReadAll, timeout)
        val c = expectMsgPF() { case GetResult("B", c: GCounter, 3, _) ⇒ c }
        c.value should be(44)
      }

    }

    enterBarrier("after-3")
  }

  "be replicated after succesful update" in {
    val changedProbe = TestProbe()
    runOn(first, second) {
      replicator ! Subscribe("C", changedProbe.ref)
    }

    runOn(first) {
      val c30 = GCounter() :+ 30
      replicator ! Update("C", c30, 0, WriteTwo, timeout)
      expectMsg(UpdateSuccess("C", 0, None))
      changedProbe.expectMsgPF() { case Changed("C", c: GCounter) ⇒ c.value } should be(30)

      replicator ! Update("Y", c30, 0, WriteTwo, timeout)
      expectMsg(UpdateSuccess("Y", 0, None))
    }
    enterBarrier("update-c30")

    runOn(second) {
      replicator ! Get("C", ReadOne, timeout)
      val c30 = expectMsgPF() { case GetResult("C", c: GCounter, 0, _) ⇒ c }
      c30.value should be(30)
      changedProbe.expectMsgPF() { case Changed("C", c: GCounter) ⇒ c.value } should be(30)

      // replicate with gossip after WriteOne
      val c31 = c30 :+ 1
      replicator ! Update("C", c31, 0, WriteOne, timeout)
      expectMsg(UpdateSuccess("C", 0, None))
      changedProbe.expectMsgPF() { case Changed("C", c: GCounter) ⇒ c.value } should be(31)

      replicator ! Delete("Y", WriteOne, timeout)
      expectMsg(DeleteSuccess("Y"))
    }
    enterBarrier("update-c31")

    runOn(first) {
      // "C" and deleted "Y" should be replicated via gossip to the other node
      within(5.seconds) {
        awaitAssert {
          replicator ! Get("C", ReadOne, timeout)
          val c = expectMsgPF() { case GetResult("C", c: GCounter, 1, _) ⇒ c }
          c.value should be(31)

          replicator ! Get("Y", ReadOne, timeout)
          expectMsg(DataDeleted("Y"))
        }
      }
      changedProbe.expectMsgPF() { case Changed("C", c: GCounter) ⇒ c.value } should be(31)
    }
    enterBarrier("verified-c31")

    // and also for concurrent updates
    runOn(first, second) {
      replicator ! Get("C", ReadOne, timeout)
      val c31 = expectMsgPF() { case GetResult("C", c: GCounter, 1, _) ⇒ c }
      c31.value should be(31)

      val c32 = c31 :+ 1
      replicator ! Update("C", c32, 1, WriteOne, timeout)
      expectMsg(UpdateSuccess("C", 1, None))

      within(5.seconds) {
        awaitAssert {
          replicator ! Get("C", ReadOne, timeout)
          val c = expectMsgPF() { case GetResult("C", c: GCounter, 2, _) ⇒ c }
          c.value should be(33)
        }
      }
    }

    enterBarrier("after-4")
  }

  "converge after partition" in {
    runOn(first) {
      val c40 = GCounter() :+ 40
      replicator ! Update("D", c40, 0, WriteTwo, timeout)
      expectMsg(UpdateSuccess("D", 0, None))

      testConductor.blackhole(first, second, Direction.Both).await
    }
    enterBarrier("blackhole-first-second")

    runOn(first, second) {
      replicator ! Get("D", ReadOne, timeout)
      val (c40, seqNo) = expectMsgPF() { case GetResult("D", c: GCounter, seqNo, _) ⇒ (c, seqNo) }
      c40.value should be(40)
      val c41 = c40 :+ 1
      replicator ! Update("D", c41, seqNo, WriteTwo, timeout)
      expectMsg(ReplicationFailure("D", seqNo, None))
      val c42 = c41 :+ 1
      replicator ! Update("D", c42, seqNo + 1, WriteTwo, timeout)
      expectMsg(ReplicationFailure("D", seqNo + 1, None))
    }
    runOn(first) {
      for (n ← 1 to 30) {
        replicator ! Update("D" + n, GCounter() :+ n, 0, WriteOne, timeout)
        expectMsg(UpdateSuccess("D" + n, 0, None))
      }
    }
    enterBarrier("updates-during-partion")

    runOn(first) {
      testConductor.passThrough(first, second, Direction.Both).await
    }
    enterBarrier("passThrough-first-second")

    runOn(first, second) {
      replicator ! Get("D", ReadTwo, timeout)
      val c44 = expectMsgPF() { case GetResult("D", c: GCounter, _, _) ⇒ c }
      c44.value should be(44)

      within(10.seconds) {
        awaitAssert {
          for (n ← 1 to 30) {
            val Key = "D" + n
            replicator ! Get(Key, ReadOne, timeout)
            expectMsgPF() { case GetResult(Key, c: GCounter, _, _) ⇒ c }.value should be(n)
          }
        }
      }
    }

    enterBarrier("after-5")
  }

  "support quorum write and read with 3 nodes with 1 unreachable" in {
    join(third, first)

    runOn(first, second, third) {
      within(10.seconds) {
        awaitAssert {
          replicator ! Internal.GetNodeCount
          expectMsg(Internal.NodeCount(3))
        }
      }
    }
    enterBarrier("3-nodes")

    runOn(first, second, third) {
      val c50 = GCounter() :+ 50
      replicator ! Update("E", c50, 0, WriteQuorum, timeout)
      expectMsg(UpdateSuccess("E", 0, None))
    }
    enterBarrier("write-inital-quorum")

    runOn(first, second, third) {
      replicator ! Get("E", ReadQuorum, timeout)
      val c150 = expectMsgPF() { case GetResult("E", c: GCounter, 1, _) ⇒ c }
      c150.value should be(150)
    }
    enterBarrier("read-inital-quorum")

    runOn(first) {
      testConductor.blackhole(first, third, Direction.Both).await
      testConductor.blackhole(second, third, Direction.Both).await
    }
    enterBarrier("blackhole-third")

    runOn(first) {
      replicator ! Get("E", ReadQuorum, timeout)
      val c150 = expectMsgPF() { case GetResult("E", c: GCounter, 1, _) ⇒ c }
      c150.value should be(150)
      val c151 = c150 :+ 1
      replicator ! Update("E", c151, 1, WriteQuorum, timeout)
      expectMsg(UpdateSuccess("E", 1, None))
    }
    enterBarrier("quorum-update-from-first")

    runOn(second) {
      replicator ! Get("E", ReadQuorum, timeout)
      val c151 = expectMsgPF() { case GetResult("E", c: GCounter, 1, _) ⇒ c }
      c151.value should be(151)
      val c152 = c151 :+ 1
      replicator ! Update("E", c152, 1, WriteQuorum, timeout)
      expectMsg(UpdateSuccess("E", 1, None))
    }

    runOn(first) {
      testConductor.passThrough(first, third, Direction.Both).await
      testConductor.passThrough(second, third, Direction.Both).await
    }
    enterBarrier("passThrough-third")

    runOn(third) {
      replicator ! Get("E", ReadQuorum, timeout)
      val c152 = expectMsgPF() { case GetResult("E", c: GCounter, 1, _) ⇒ c }
      c152.value should be(152)
    }

    enterBarrier("after-6")
  }

  "converge after many concurrent updates" in within(10.seconds) {
    runOn(first, second, third) {
      var c = GCounter()
      for (i ← 0 until 100) {
        c :+= 1
        replicator ! Update("F", c, i, WriteTwo, timeout)
      }
      val results = receiveN(100)
      results.map(_.getClass).toSet should be(Set(classOf[UpdateSuccess]))
    }
    enterBarrier("100-updates-done")
    runOn(first, second, third) {
      replicator ! Get("F", ReadTwo, timeout)
      val c = expectMsgPF() { case GetResult("F", c: GCounter, 100, _) ⇒ c }
      c.value should be(3 * 100)
    }
    enterBarrier("after-7")
  }

}

