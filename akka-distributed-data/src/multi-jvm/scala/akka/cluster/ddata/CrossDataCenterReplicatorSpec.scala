/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.Cluster
import akka.cluster.ClusterSettings.DataCenter
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object CrossDataCenterReplicatorSpec extends MultiNodeConfig {
  val dc1First = role("first")
  val dc1Second = role("second")
  val dc1Third = role("third")
  val dc2First = role("fourth")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.log-dead-letters-during-shutdown = off
    #akka.cluster.distributed-data.delta-crdt.enabled = off
    """))

  nodeConfig(dc1First, dc1Second, dc1Third) {
    ConfigFactory.parseString("akka.cluster.multi-data-center.self-data-center = DC1")
  }

  nodeConfig(dc2First) {
    ConfigFactory.parseString("akka.cluster.multi-data-center.self-data-center = DC2")
  }

  testTransport(on = true)

}

class CrossDataCenterReplicatorSpecMultiJvmNode1 extends CrossDataCenterReplicatorSpec
class CrossDataCenterReplicatorSpecMultiJvmNode2 extends CrossDataCenterReplicatorSpec
class CrossDataCenterReplicatorSpecMultiJvmNode3 extends CrossDataCenterReplicatorSpec
class CrossDataCenterReplicatorSpecMultiJvmNode4 extends CrossDataCenterReplicatorSpec

class CrossDataCenterReplicatorSpec extends MultiNodeSpec(CrossDataCenterReplicatorSpec) with STMultiNodeSpec with ImplicitSender {
  import Replicator._
  import CrossDataCenterReplicatorSpec._

  override def initialParticipants = roles.size

  implicit val cluster = Cluster(system)
  val replicator = system.actorOf(Replicator.props(
    ReplicatorSettings(system).withGossipInterval(1.second).withMaxDeltaElements(10)), "replicator")
  val timeout = 3.seconds.dilated
  val writeTwo = onlyInFirstDc(WriteTo(2, timeout))
  val writeMajority = onlyInFirstDc(WriteMajority(timeout))
  val writeAll = onlyInFirstDc(WriteAll(timeout))
  val readTwo = ReadFrom(2, timeout)
  val readAll = ReadAll(timeout)
  val readMajority = ReadMajority(timeout)

  val KeyA = GCounterKey("A")
  val KeyB = GCounterKey("B")
  val KeyC = GCounterKey("C")
  val KeyD = GCounterKey("D")
  val KeyE = GCounterKey("E")
  val KeyE2 = GCounterKey("E2")
  val KeyF = GCounterKey("F")
  val KeyG = ORSetKey[String]("G")
  val KeyH = ORMapKey[String, Flag]("H")
  val KeyI = GSetKey[String]("I")
  val KeyJ = GSetKey[String]("J")
  val KeyX = GCounterKey("X")
  val KeyY = GCounterKey("Y")
  val KeyZ = GCounterKey("Z")

  var afterCounter = 0
  def enterBarrierAfterTestStep(): Unit = {
    afterCounter += 1
    enterBarrier("after-" + afterCounter)
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  def onlyInFirstDc(consistency: WriteConsistency): WriteConsistency =
    DataCenterWriteConsistency(timeout, Map("DC1" -> consistency))

  "Cluster CRDT" must {

    "work in single node cluster" in {
      join(dc1First, dc1First)
      join(dc2First, dc1First)

      runOn(dc1First) {

        within(5.seconds) {
          awaitAssert {
            replicator ! GetReplicaCount
            expectMsg(ReplicaCount(1))
          }
        }

        val changedProbe = TestProbe()
        replicator ! Subscribe(KeyA, changedProbe.ref)
        replicator ! Subscribe(KeyX, changedProbe.ref)

        replicator ! Get(KeyA, ReadLocal)
        expectMsg(NotFound(KeyA, None))

        val c3 = GCounter() + 3
        replicator ! Update(KeyA, GCounter(), WriteLocal)(_ + 3)
        expectMsg(UpdateSuccess(KeyA, None))
        replicator ! Get(KeyA, ReadLocal)
        expectMsg(GetSuccess(KeyA, None)(c3)).dataValue should be(c3)
        changedProbe.expectMsg(Changed(KeyA)(c3)).dataValue should be(c3)

        val changedProbe2 = TestProbe()
        replicator ! Subscribe(KeyA, changedProbe2.ref)
        changedProbe2.expectMsg(Changed(KeyA)(c3)).dataValue should be(c3)

        val c4 = c3 + 1
        // too strong consistency level
        replicator ! Update(KeyA, GCounter(), writeTwo)(_ + 1)
        expectMsg(timeout + 1.second, UpdateTimeout(KeyA, None))
        replicator ! Get(KeyA, ReadLocal)
        expectMsg(GetSuccess(KeyA, None)(c4)).dataValue should be(c4)
        changedProbe.expectMsg(Changed(KeyA)(c4)).dataValue should be(c4)

        val c5 = c4 + 1
        // too strong consistency level
        replicator ! Update(KeyA, GCounter(), writeMajority)(_ + 1)
        expectMsg(UpdateSuccess(KeyA, None))
        replicator ! Get(KeyA, readMajority)
        expectMsg(GetSuccess(KeyA, None)(c5)).dataValue should be(c5)
        changedProbe.expectMsg(Changed(KeyA)(c5)).dataValue should be(c5)

        val c6 = c5 + 1
        replicator ! Update(KeyA, GCounter(), writeAll)(_ + 1)
        expectMsg(UpdateSuccess(KeyA, None))
        replicator ! Get(KeyA, readAll)
        expectMsg(GetSuccess(KeyA, None)(c6)).dataValue should be(c6)
        changedProbe.expectMsg(Changed(KeyA)(c6)).dataValue should be(c6)

        val c9 = GCounter() + 9
        replicator ! Update(KeyX, GCounter(), WriteLocal)(_ + 9)
        expectMsg(UpdateSuccess(KeyX, None))
        changedProbe.expectMsg(Changed(KeyX)(c9)).dataValue should be(c9)
        replicator ! Delete(KeyX, WriteLocal, Some(777))
        expectMsg(DeleteSuccess(KeyX, Some(777)))
        changedProbe.expectMsg(Deleted(KeyX))
        replicator ! Get(KeyX, ReadLocal, Some(789))
        expectMsg(DataDeleted(KeyX, Some(789)))
        replicator ! Get(KeyX, readAll, Some(456))
        expectMsg(DataDeleted(KeyX, Some(456)))
        replicator ! Update(KeyX, GCounter(), WriteLocal, Some(123))(_ + 1)
        expectMsg(DataDeleted(KeyX, Some(123)))
        replicator ! Delete(KeyX, WriteLocal, Some(555))
        expectMsg(DataDeleted(KeyX, Some(555)))

        replicator ! GetKeyIds
        expectMsg(GetKeyIdsResult(Set("A")))
      }

      enterBarrierAfterTestStep()
    }
  }

  "merge the update with existing value" in {
    runOn(dc1First) {
      // in case user is not using the passed in existing value
      replicator ! Update(KeyJ, GSet(), WriteLocal)(_ + "a" + "b")
      expectMsg(UpdateSuccess(KeyJ, None))
      replicator ! Update(KeyJ, GSet(), WriteLocal)(_ ⇒ GSet.empty[String] + "c") // normal usage would be `_ + "c"`
      expectMsg(UpdateSuccess(KeyJ, None))
      replicator ! Get(KeyJ, ReadLocal)
      val s = expectMsgPF() { case g @ GetSuccess(KeyJ, _) ⇒ g.get(KeyJ) }
      s should ===(GSet.empty[String] + "a" + "b" + "c")
    }
    enterBarrierAfterTestStep()
  }

  "reply with ModifyFailure if exception is thrown by modify function" in {
    val e = new RuntimeException("errr")
    replicator ! Update(KeyA, GCounter(), WriteLocal)(_ ⇒ throw e)
    expectMsgType[ModifyFailure[_]].cause should be(e)
  }

  "replicate values to new node" in {
    join(dc1Second, dc1First)

    runOn(dc1First, dc1Second) {
      within(10.seconds) {
        awaitAssert {
          replicator ! GetReplicaCount
          expectMsg(ReplicaCount(2))
        }
      }
    }

    enterBarrier("2-nodes")

    runOn(dc1Second) {
      val changedProbe = TestProbe()
      replicator ! Subscribe(KeyA, changedProbe.ref)
      // "A" should be replicated via gossip to the new node
      within(5.seconds) {
        awaitAssert {
          replicator ! Get(KeyA, ReadLocal)
          val c = expectMsgPF() { case g @ GetSuccess(KeyA, _) ⇒ g.get(KeyA) }
          c.value should be(6)
        }
      }
      val c = changedProbe.expectMsgPF() { case c @ Changed(KeyA) ⇒ c.get(KeyA) }
      c.value should be(6)
    }

    enterBarrierAfterTestStep()
  }

  "work in 2 node cluster" in {

    runOn(dc1First, dc1Second) {
      // start with 20 on both nodes
      replicator ! Update(KeyB, GCounter(), WriteLocal)(_ + 20)
      expectMsg(UpdateSuccess(KeyB, None))

      // add 1 on both nodes using WriteTwo
      replicator ! Update(KeyB, GCounter(), writeTwo)(_ + 1)
      expectMsg(UpdateSuccess(KeyB, None))

      // the total, after replication should be 42
      awaitAssert {
        replicator ! Get(KeyB, readTwo)
        val c = expectMsgPF() { case g @ GetSuccess(KeyB, _) ⇒ g.get(KeyB) }
        c.value should be(42)
      }
    }
    enterBarrier("update-42")

    runOn(dc1First, dc1Second) {
      // add 1 on both nodes using WriteAll
      replicator ! Update(KeyB, GCounter(), writeAll)(_ + 1)
      expectMsg(UpdateSuccess(KeyB, None))

      // the total, after replication should be 44
      awaitAssert {
        replicator ! Get(KeyB, readAll)
        val c = expectMsgPF() { case g @ GetSuccess(KeyB, _) ⇒ g.get(KeyB) }
        c.value should be(44)
      }
    }
    enterBarrier("update-44")

    runOn(dc1First, dc1Second) {
      // add 1 on both nodes using WriteMajority
      replicator ! Update(KeyB, GCounter(), writeMajority)(_ + 1)
      expectMsg(UpdateSuccess(KeyB, None))

      // the total, after replication should be 46
      awaitAssert {
        replicator ! Get(KeyB, readMajority)
        val c = expectMsgPF() { case g @ GetSuccess(KeyB, _) ⇒ g.get(KeyB) }
        c.value should be(46)
      }
    }

    enterBarrierAfterTestStep()
  }

  "be replicated after succesful update" in {
    val changedProbe = TestProbe()
    runOn(dc1First, dc1Second) {
      replicator ! Subscribe(KeyC, changedProbe.ref)
    }

    runOn(dc1First) {
      replicator ! Update(KeyC, GCounter(), writeTwo)(_ + 30)
      expectMsg(UpdateSuccess(KeyC, None))
      changedProbe.expectMsgPF() { case c @ Changed(KeyC) ⇒ c.get(KeyC).value } should be(30)

      replicator ! Update(KeyY, GCounter(), writeTwo)(_ + 30)
      expectMsg(UpdateSuccess(KeyY, None))

      replicator ! Update(KeyZ, GCounter(), writeMajority)(_ + 30)
      expectMsg(UpdateSuccess(KeyZ, None))
    }
    enterBarrier("update-c30")

    runOn(dc1Second) {
      replicator ! Get(KeyC, ReadLocal)
      val c30 = expectMsgPF() { case g @ GetSuccess(KeyC, _) ⇒ g.get(KeyC) }
      c30.value should be(30)
      changedProbe.expectMsgPF() { case c @ Changed(KeyC) ⇒ c.get(KeyC).value } should be(30)

      // replicate with gossip after WriteLocal
      replicator ! Update(KeyC, GCounter(), WriteLocal)(_ + 1)
      expectMsg(UpdateSuccess(KeyC, None))
      changedProbe.expectMsgPF() { case c @ Changed(KeyC) ⇒ c.get(KeyC).value } should be(31)

      replicator ! Delete(KeyY, WriteLocal, Some(777))
      expectMsg(DeleteSuccess(KeyY, Some(777)))

      replicator ! Get(KeyZ, readMajority)
      expectMsgPF() { case g @ GetSuccess(KeyZ, _) ⇒ g.get(KeyZ).value } should be(30)
    }
    enterBarrier("update-c31")

    runOn(dc1First) {
      // KeyC and deleted KeyY should be replicated via gossip to the other node
      within(5.seconds) {
        awaitAssert {
          replicator ! Get(KeyC, ReadLocal)
          val c = expectMsgPF() { case g @ GetSuccess(KeyC, _) ⇒ g.get(KeyC) }
          c.value should be(31)

          replicator ! Get(KeyY, ReadLocal, Some(777))
          expectMsg(DataDeleted(KeyY, Some(777)))
        }
      }
      changedProbe.expectMsgPF() { case c @ Changed(KeyC) ⇒ c.get(KeyC).value } should be(31)
    }
    enterBarrier("verified-c31")

    // and also for concurrent updates
    runOn(dc1First, dc1Second) {
      replicator ! Get(KeyC, ReadLocal)
      val c31 = expectMsgPF() { case g @ GetSuccess(KeyC, _) ⇒ g.get(KeyC) }
      c31.value should be(31)

      replicator ! Update(KeyC, GCounter(), WriteLocal)(_ + 1)
      expectMsg(UpdateSuccess(KeyC, None))

      within(5.seconds) {
        awaitAssert {
          replicator ! Get(KeyC, ReadLocal)
          val c = expectMsgPF() { case g @ GetSuccess(KeyC, _) ⇒ g.get(KeyC) }
          c.value should be(33)
        }
      }
    }

    enterBarrierAfterTestStep()
  }

  "converge after partition" in {
    runOn(dc1First) {
      replicator ! Update(KeyD, GCounter(), writeTwo)(_ + 40)
      expectMsg(UpdateSuccess(KeyD, None))

      testConductor.blackhole(dc1First, dc1Second, Direction.Both).await
    }
    enterBarrier("blackhole-first-second")

    runOn(dc1First, dc1Second) {
      replicator ! Get(KeyD, ReadLocal)
      val c40 = expectMsgPF() { case g @ GetSuccess(KeyD, _) ⇒ g.get(KeyD) }
      c40.value should be(40)
      replicator ! Update(KeyD, GCounter() + 1, writeTwo)(_ + 1)
      expectMsg(timeout + 1.second, UpdateTimeout(KeyD, None))
      replicator ! Update(KeyD, GCounter(), writeTwo)(_ + 1)
      expectMsg(timeout + 1.second, UpdateTimeout(KeyD, None))
    }
    runOn(dc1First) {
      for (n ← 1 to 30) {
        val KeyDn = GCounterKey("D" + n)
        replicator ! Update(KeyDn, GCounter(), WriteLocal)(_ + n)
        expectMsg(UpdateSuccess(KeyDn, None))
      }
    }
    enterBarrier("updates-during-partion")

    runOn(dc1First) {
      testConductor.passThrough(dc1First, dc1Second, Direction.Both).await
    }
    enterBarrier("passThrough-first-second")

    runOn(dc1First, dc1Second) {
      replicator ! Get(KeyD, readTwo)
      val c44 = expectMsgPF() { case g @ GetSuccess(KeyD, _) ⇒ g.get(KeyD) }
      c44.value should be(44)

      within(10.seconds) {
        awaitAssert {
          for (n ← 1 to 30) {
            val KeyDn = GCounterKey("D" + n)
            replicator ! Get(KeyDn, ReadLocal)
            expectMsgPF() { case g @ GetSuccess(KeyDn, _) ⇒ g.get(KeyDn) }.value should be(n)
          }
        }
      }
    }

    enterBarrierAfterTestStep()
  }

  "support majority quorum write and read with 3 nodes with 1 unreachable" in {
    join(dc1Third, dc1First)

    runOn(dc1First, dc1Second, dc1Third) {
      within(10.seconds) {
        awaitAssert {
          replicator ! GetReplicaCount
          expectMsg(ReplicaCount(3))
        }
      }
    }
    enterBarrier("3-nodes")

    runOn(dc1First, dc1Second, dc1Third) {
      replicator ! Update(KeyE, GCounter(), writeMajority)(_ + 50)
      expectMsg(UpdateSuccess(KeyE, None))
    }
    enterBarrier("write-inital-majority")

    runOn(dc1First, dc1Second, dc1Third) {
      replicator ! Get(KeyE, readMajority)
      val c150 = expectMsgPF() { case g @ GetSuccess(KeyE, _) ⇒ g.get(KeyE) }
      c150.value should be(150)
    }
    enterBarrier("read-inital-majority")

    runOn(dc1First) {
      testConductor.blackhole(dc1First, dc1Third, Direction.Both).await
      testConductor.blackhole(dc1Second, dc1Third, Direction.Both).await
    }
    enterBarrier("blackhole-third")

    runOn(dc1Second) {
      replicator ! Update(KeyE, GCounter(), WriteLocal)(_ + 1)
      expectMsg(UpdateSuccess(KeyE, None))
    }
    enterBarrier("local-update-from-second")

    runOn(dc1First) {
      // ReadMajority should retrive the previous update from second, before applying the modification
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      replicator.tell(Get(KeyE, readMajority), probe2.ref)
      probe2.expectMsgType[GetSuccess[_]]
      replicator.tell(Update(KeyE, GCounter(), writeMajority, None) { data ⇒
        probe1.ref ! data.value
        data + 1
      }, probe2.ref)
      // verify read your own writes, without waiting for the UpdateSuccess reply
      // note that the order of the replies are not defined, and therefore we use separate probes
      val probe3 = TestProbe()
      replicator.tell(Get(KeyE, readMajority), probe3.ref)
      probe1.expectMsg(151)
      probe2.expectMsg(UpdateSuccess(KeyE, None))
      val c152 = probe3.expectMsgPF() { case g @ GetSuccess(KeyE, _) ⇒ g.get(KeyE) }
      c152.value should be(152)
    }
    enterBarrier("majority-update-from-first")

    runOn(dc1Second) {
      val probe1 = TestProbe()
      replicator.tell(Get(KeyE, readMajority), probe1.ref)
      probe1.expectMsgType[GetSuccess[_]]
      replicator.tell(Update(KeyE, GCounter(), writeMajority, Some(153))(_ + 1), probe1.ref)
      // verify read your own writes, without waiting for the UpdateSuccess reply
      // note that the order of the replies are not defined, and therefore we use separate probes
      val probe2 = TestProbe()
      replicator.tell(Update(KeyE, GCounter(), writeMajority, Some(154))(_ + 1), probe2.ref)
      val probe3 = TestProbe()
      replicator.tell(Update(KeyE, GCounter(), writeMajority, Some(155))(_ + 1), probe3.ref)
      val probe5 = TestProbe()
      replicator.tell(Get(KeyE, readMajority), probe5.ref)
      probe1.expectMsg(UpdateSuccess(KeyE, Some(153)))
      probe2.expectMsg(UpdateSuccess(KeyE, Some(154)))
      probe3.expectMsg(UpdateSuccess(KeyE, Some(155)))
      val c155 = probe5.expectMsgPF() { case g @ GetSuccess(KeyE, _) ⇒ g.get(KeyE) }
      c155.value should be(155)
    }
    enterBarrier("majority-update-from-second")

    runOn(dc1First, dc1Second) {
      replicator ! Get(KeyE2, readAll, Some(998))
      expectMsg(timeout + 1.second, GetFailure(KeyE2, Some(998)))
      replicator ! Get(KeyE2, ReadLocal)
      expectMsg(NotFound(KeyE2, None))
    }
    enterBarrier("read-all-fail-update")

    runOn(dc1First) {
      testConductor.passThrough(dc1First, dc1Third, Direction.Both).await
      testConductor.passThrough(dc1Second, dc1Third, Direction.Both).await
    }
    enterBarrier("passThrough-third")

    runOn(dc1Third) {
      replicator ! Get(KeyE, readMajority)
      val c155 = expectMsgPF() { case g @ GetSuccess(KeyE, _) ⇒ g.get(KeyE) }
      c155.value should be(155)
    }

    enterBarrierAfterTestStep()
  }

  "converge after many concurrent updates" in within(10.seconds) {
    runOn(dc1First, dc1Second, dc1Third) {
      var c = GCounter()
      for (i ← 0 until 100) {
        c += 1
        replicator ! Update(KeyF, GCounter(), writeTwo)(_ + 1)
      }
      val results = receiveN(100)
      results.map(_.getClass).toSet should be(Set(classOf[UpdateSuccess[_]]))
    }
    enterBarrier("100-updates-done")
    runOn(dc1First, dc1Second, dc1Third) {
      replicator ! Get(KeyF, readTwo)
      val c = expectMsgPF() { case g @ GetSuccess(KeyF, _) ⇒ g.get(KeyF) }
      c.value should be(3 * 100)
    }
    enterBarrierAfterTestStep()
  }

  "read-repair happens before GetSuccess" in {
    runOn(dc1First) {
      replicator ! Update(KeyG, ORSet(), writeTwo)(_ + "a" + "b")
      expectMsgType[UpdateSuccess[_]]
    }
    enterBarrier("a-b-added-to-G")
    runOn(dc1Second) {
      replicator ! Get(KeyG, readAll)
      expectMsgPF() { case g @ GetSuccess(KeyG, _) ⇒ g.get(KeyG).elements } should be(Set("a", "b"))
      replicator ! Get(KeyG, ReadLocal)
      expectMsgPF() { case g @ GetSuccess(KeyG, _) ⇒ g.get(KeyG).elements } should be(Set("a", "b"))
    }
    enterBarrierAfterTestStep()
  }

  "check that a remote update and a local update both cause a change event to emit with the merged data" in {
    val changedProbe = TestProbe()

    runOn(dc1Second) {
      replicator ! Subscribe(KeyH, changedProbe.ref)
      replicator ! Update(KeyH, ORMap.empty[String, Flag], writeTwo)(_ + ("a" → Flag(enabled = false)))
      changedProbe.expectMsgPF() { case c @ Changed(KeyH) ⇒ c.get(KeyH).entries } should be(Map("a" → Flag(enabled = false)))
    }

    enterBarrier("update-h1")

    runOn(dc1First) {
      replicator ! Update(KeyH, ORMap.empty[String, Flag], writeTwo)(_ + ("a" → Flag(enabled = true)))
    }

    runOn(dc1Second) {
      changedProbe.expectMsgPF() { case c @ Changed(KeyH) ⇒ c.get(KeyH).entries } should be(Map("a" → Flag(enabled = true)))

      replicator ! Update(KeyH, ORMap.empty[String, Flag], writeTwo)(_ + ("b" → Flag(enabled = true)))
      changedProbe.expectMsgPF() { case c @ Changed(KeyH) ⇒ c.get(KeyH).entries } should be(
        Map("a" → Flag(enabled = true), "b" → Flag(enabled = true)))
    }

    enterBarrierAfterTestStep()
  }

  "avoid duplicate change events for same data" in {
    val changedProbe = TestProbe()
    replicator ! Subscribe(KeyI, changedProbe.ref)
    enterBarrier("subscribed-I")

    runOn(dc1Second) {
      replicator ! Update(KeyI, GSet.empty[String], writeTwo)(a ⇒ a.add("a"))
    }

    within(5.seconds) { // gossip to third
      changedProbe.expectMsgPF() { case c @ Changed(KeyI) ⇒ c.get(KeyI).elements } should be(Set("a"))
    }

    enterBarrier("update-I")

    runOn(dc1First) {
      replicator ! Update(KeyI, GSet.empty[String], writeTwo)(_ + "a")
    }

    changedProbe.expectNoMsg(1.second)

    enterBarrierAfterTestStep()
  }

}

