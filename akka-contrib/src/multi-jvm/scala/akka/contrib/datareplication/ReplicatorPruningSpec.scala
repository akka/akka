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

object ReplicatorPruningSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.log-dead-letters-during-shutdown = off
    """))

}

class ReplicatorPruningSpecMultiJvmNode1 extends ReplicatorPruningSpec
class ReplicatorPruningSpecMultiJvmNode2 extends ReplicatorPruningSpec
class ReplicatorPruningSpecMultiJvmNode3 extends ReplicatorPruningSpec

class ReplicatorPruningSpec extends MultiNodeSpec(ReplicatorPruningSpec) with STMultiNodeSpec with ImplicitSender {
  import ReplicatorPruningSpec._
  import Replicator._

  override def initialParticipants = roles.size

  implicit val cluster = Cluster(system)
  val maxPruningDissemination = 3.seconds
  val replicator = system.actorOf(Replicator.props(role = None, gossipInterval = 1.second,
    pruningInterval = 1.second, maxPruningDissemination = maxPruningDissemination), "replicator")
  val timeout = 2.seconds.dilated

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Pruning of CRDT" must {

    "move data from removed node" in {
      join(first, first)
      join(second, first)
      join(third, first)

      within(5.seconds) {
        awaitAssert {
          replicator ! Internal.GetNodeCount
          expectMsg(Internal.NodeCount(3))
        }
      }

      // we need the UniqueAddress
      val memberProbe = TestProbe()
      cluster.subscribe(memberProbe.ref, initialStateMode = InitialStateAsEvents, classOf[MemberUp])
      val thirdUniqueAddress = memberProbe.fishForMessage(3.seconds) {
        case MemberUp(m) if m.address == node(third).address ⇒ true
        case _ ⇒ false
      }.asInstanceOf[MemberUp].member.uniqueAddress

      val c3 = GCounter() :+ 3
      replicator ! Update("A", c3, 0, WriteAll, timeout)
      expectMsg(UpdateSuccess("A", 0, None))

      val s = ORSet() :+ "a" :+ "b" :+ "c"
      replicator ! Update("B", s, 0, WriteAll, timeout)
      expectMsg(UpdateSuccess("B", 0, None))

      val m = PNCounterMap() increment "x" increment "y"
      replicator ! Update("C", m, 0, WriteAll, timeout)
      expectMsg(UpdateSuccess("C", 0, None))

      enterBarrier("updates-done")

      replicator ! Get("A", ReadOne, timeout)
      val oldCounter = expectMsgType[GetSuccess].data.asInstanceOf[GCounter]
      oldCounter.value should be(9)

      replicator ! Get("B", ReadOne, timeout)
      val oldSet = expectMsgType[GetSuccess].data.asInstanceOf[ORSet]
      oldSet.value should be(Set("a", "b", "c"))

      replicator ! Get("C", ReadOne, timeout)
      val oldMap = expectMsgType[GetSuccess].data.asInstanceOf[PNCounterMap]
      oldMap.get("x") should be(Some(3))
      oldMap.get("y") should be(Some(3))

      enterBarrier("get-old")

      runOn(first) {
        cluster.leave(node(third).address)
      }

      runOn(first, second) {
        within(15.seconds) {
          awaitAssert {
            replicator ! Internal.GetNodeCount
            expectMsg(Internal.NodeCount(2))
          }
        }
      }
      enterBarrier("third-removed")

      runOn(first, second) {
        within(15.seconds) {
          awaitAssert {
            replicator ! Get("A", ReadOne, timeout)
            expectMsgPF() {
              case GetSuccess(_, c: GCounter, _, _) ⇒
                c.value should be(9)
                c.needPruningFrom(thirdUniqueAddress) should be(false)
            }
          }
        }
        within(5.seconds) {
          awaitAssert {
            replicator ! Get("B", ReadOne, timeout)
            expectMsgPF() {
              case GetSuccess(_, s: ORSet, _, _) ⇒
                s.value should be(Set("a", "b", "c"))
                s.needPruningFrom(thirdUniqueAddress) should be(false)
            }
          }
        }
        within(5.seconds) {
          awaitAssert {
            replicator ! Get("C", ReadOne, timeout)
            expectMsgPF() {
              case GetSuccess(_, m: PNCounterMap, _, _) ⇒
                m.entries should be(Map("x" -> 3L, "y" -> 3L))
                m.needPruningFrom(thirdUniqueAddress) should be(false)
            }
          }
        }
      }
      enterBarrier("pruning-done")

      // on one of the nodes the seqNo has been updated, 
      // client might think the expected seqNo is 1 and then Update should reply with WrongSeqNo
      def updateAfterPruning(expectedValue: Int): Unit = {
        val c = oldCounter :+ 1
        replicator ! Update("A", c, seqNo = 1, WriteAll, timeout, None)
        expectMsgPF() {
          case UpdateSuccess("A", 1, _) ⇒
            replicator ! Get("A", ReadOne, timeout)
            val retrieved = expectMsgType[GetSuccess].data.asInstanceOf[GCounter]
            retrieved.value should be(expectedValue)
          case WrongSeqNo("A", currentCrdt: GCounter, 1, currentSeqNo, _) ⇒
            replicator ! Update("A", currentCrdt :+ 1, seqNo = currentSeqNo, WriteAll, timeout, None)
            expectMsg(UpdateSuccess("A", currentSeqNo, None))
            replicator ! Get("A", ReadOne, timeout)
            val retrieved = expectMsgType[GetSuccess].data.asInstanceOf[GCounter]
            retrieved.value should be(expectedValue)
        }
      }
      runOn(first) {
        updateAfterPruning(expectedValue = 10)
      }
      enterBarrier("update-first-after-pruning")

      runOn(second) {
        updateAfterPruning(expectedValue = 11)
      }
      enterBarrier("update-second-after-pruning")

      // after pruning performed and maxDissemination it is tombstoned
      // and we should still not be able to update with data from removed node
      expectNoMsg(maxPruningDissemination + 3.seconds)

      runOn(first) {
        updateAfterPruning(expectedValue = 12)
      }
      enterBarrier("update-first-after-tombstone")

      runOn(second) {
        updateAfterPruning(expectedValue = 13)
      }
      enterBarrier("update-second-after-tombstone")

      enterBarrier("after-1")
    }
  }

}

