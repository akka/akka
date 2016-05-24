/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import scala.concurrent.duration._

import akka.cluster.Cluster
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberUp
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

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
  val replicator = system.actorOf(Replicator.props(
    ReplicatorSettings(system).withGossipInterval(1.second)
      .withPruning(pruningInterval = 1.second, maxPruningDissemination)), "replicator")
  val timeout = 2.seconds.dilated

  val KeyA = GCounterKey("A")
  val KeyB = ORSetKey[String]("B")
  val KeyC = PNCounterMapKey("C")

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
          replicator ! GetReplicaCount
          expectMsg(ReplicaCount(3))
        }
      }

      // we need the UniqueAddress
      val memberProbe = TestProbe()
      cluster.subscribe(memberProbe.ref, initialStateMode = InitialStateAsEvents, classOf[MemberUp])
      val thirdUniqueAddress = {
        val member = memberProbe.fishForMessage(3.seconds) {
          case MemberUp(m) if m.address == node(third).address ⇒ true
          case _ ⇒ false
        }.asInstanceOf[MemberUp].member
        member.uniqueAddress
      }

      replicator ! Update(KeyA, GCounter(), WriteAll(timeout))(_ + 3)
      expectMsg(UpdateSuccess(KeyA, None))

      replicator ! Update(KeyB, ORSet(), WriteAll(timeout))(_ + "a" + "b" + "c")
      expectMsg(UpdateSuccess(KeyB, None))

      replicator ! Update(KeyC, PNCounterMap(), WriteAll(timeout))(_ increment "x" increment "y")
      expectMsg(UpdateSuccess(KeyC, None))

      enterBarrier("updates-done")

      replicator ! Get(KeyA, ReadLocal)
      val oldCounter = expectMsgType[GetSuccess[GCounter]].dataValue
      oldCounter.value should be(9)

      replicator ! Get(KeyB, ReadLocal)
      val oldSet = expectMsgType[GetSuccess[ORSet[String]]].dataValue
      oldSet.elements should be(Set("a", "b", "c"))

      replicator ! Get(KeyC, ReadLocal)
      val oldMap = expectMsgType[GetSuccess[PNCounterMap]].dataValue
      oldMap.get("x") should be(Some(3))
      oldMap.get("y") should be(Some(3))

      enterBarrier("get-old")

      runOn(first) {
        cluster.leave(node(third).address)
      }

      runOn(first, second) {
        within(15.seconds) {
          awaitAssert {
            replicator ! GetReplicaCount
            expectMsg(ReplicaCount(2))
          }
        }
      }
      enterBarrier("third-removed")

      runOn(first, second) {
        within(15.seconds) {
          awaitAssert {
            replicator ! Get(KeyA, ReadLocal)
            expectMsgPF() {
              case g @ GetSuccess(KeyA, _) ⇒
                g.get(KeyA).value should be(9)
                g.get(KeyA).needPruningFrom(thirdUniqueAddress) should be(false)
            }
          }
        }
        within(5.seconds) {
          awaitAssert {
            replicator ! Get(KeyB, ReadLocal)
            expectMsgPF() {
              case g @ GetSuccess(KeyB, _) ⇒
                g.get(KeyB).elements should be(Set("a", "b", "c"))
                g.get(KeyB).needPruningFrom(thirdUniqueAddress) should be(false)
            }
          }
        }
        within(5.seconds) {
          awaitAssert {
            replicator ! Get(KeyC, ReadLocal)
            expectMsgPF() {
              case g @ GetSuccess(KeyC, _) ⇒
                g.get(KeyC).entries should be(Map("x" → 3L, "y" → 3L))
                g.get(KeyC).needPruningFrom(thirdUniqueAddress) should be(false)
            }
          }
        }
      }
      enterBarrier("pruning-done")

      // on one of the nodes the data has been updated by the pruning,
      // client can update anyway
      def updateAfterPruning(expectedValue: Int): Unit = {
        replicator ! Update(KeyA, GCounter(), WriteAll(timeout), None)(_ + 1)
        expectMsgPF() {
          case UpdateSuccess(KeyA, _) ⇒
            replicator ! Get(KeyA, ReadLocal)
            val retrieved = expectMsgType[GetSuccess[GCounter]].dataValue
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

