/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import akka.cluster.Cluster
import akka.cluster.ddata.Replicator._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object ReplicatorDeltaSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.log-dead-letters-during-shutdown = off
    """))

  testTransport(on = true)

  sealed trait Op
  final case class Delay(n: Int) extends Op
  final case class Incr(key: PNCounterKey, n: Int, consistency: WriteConsistency) extends Op
  final case class Decr(key: PNCounterKey, n: Int, consistency: WriteConsistency) extends Op

  val timeout = 5.seconds
  val writeTwo = WriteTo(2, timeout)
  val writeMajority = WriteMajority(timeout)

  val KeyA = PNCounterKey("A")
  val KeyB = PNCounterKey("B")
  val KeyC = PNCounterKey("C")

  def generateOperations(): Vector[Op] = {
    val rnd = ThreadLocalRandom.current()

    def consistency(): WriteConsistency = {
      rnd.nextInt(100) match {
        case n if n < 90  ⇒ WriteLocal
        case n if n < 95  ⇒ writeTwo
        case n if n < 100 ⇒ writeMajority
      }
    }

    def key(): PNCounterKey = {
      rnd.nextInt(3) match {
        case 0 ⇒ KeyA
        case 1 ⇒ KeyB
        case 2 ⇒ KeyC
      }
    }

    (0 to (20 + rnd.nextInt(10))).map { _ ⇒
      rnd.nextInt(3) match {
        case 0 ⇒ Delay(rnd.nextInt(500))
        case 1 ⇒ Incr(key(), rnd.nextInt(100), consistency())
        case 2 ⇒ Decr(key(), rnd.nextInt(10), consistency())
      }
    }.toVector
  }

}

class ReplicatorDeltaSpecMultiJvmNode1 extends ReplicatorDeltaSpec
class ReplicatorDeltaSpecMultiJvmNode2 extends ReplicatorDeltaSpec
class ReplicatorDeltaSpecMultiJvmNode3 extends ReplicatorDeltaSpec
class ReplicatorDeltaSpecMultiJvmNode4 extends ReplicatorDeltaSpec

class ReplicatorDeltaSpec extends MultiNodeSpec(ReplicatorDeltaSpec) with STMultiNodeSpec with ImplicitSender {
  import Replicator._
  import ReplicatorDeltaSpec._

  override def initialParticipants = roles.size

  implicit val cluster = Cluster(system)
  val fullStateReplicator = system.actorOf(Replicator.props(
    ReplicatorSettings(system).withGossipInterval(1.second).withDeltaCrdtEnabled(false)), "fullStateReplicator")
  val deltaReplicator = {
    val r = system.actorOf(Replicator.props(ReplicatorSettings(system)), "deltaReplicator")
    r ! Replicator.Internal.TestFullStateGossip(enabled = false)
    r
  }

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

  "delta-CRDT" must {
    "join cluster" in {
      join(first, first)
      join(second, first)
      join(third, first)
      join(fourth, first)

      within(15.seconds) {
        awaitAssert {
          fullStateReplicator ! GetReplicaCount
          expectMsg(ReplicaCount(4))
        }
      }

      enterBarrierAfterTestStep()
    }

    "propagate delta" in {
      join(first, first)
      join(second, first)
      join(third, first)
      join(fourth, first)

      within(15.seconds) {
        awaitAssert {
          fullStateReplicator ! GetReplicaCount
          expectMsg(ReplicaCount(4))
        }
      }
      enterBarrier("ready")

      runOn(first) {
        fullStateReplicator ! Update(KeyA, PNCounter.empty, WriteLocal)(_ + 1)
        deltaReplicator ! Update(KeyA, PNCounter.empty, WriteLocal)(_ + 1)
      }
      enterBarrier("updated-1")

      within(5.seconds) {
        awaitAssert {
          val p = TestProbe()
          deltaReplicator.tell(Get(KeyA, ReadLocal), p.ref)
          p.expectMsgType[GetSuccess[PNCounter]].dataValue.getValue.intValue should be(1)
        }
        awaitAssert {
          val p = TestProbe()
          deltaReplicator.tell(Get(KeyA, ReadLocal), p.ref)
          p.expectMsgType[GetSuccess[PNCounter]].dataValue.getValue.intValue should be(1)
        }
      }

      enterBarrierAfterTestStep()
    }

    "be eventually consistent" in {
      val operations = generateOperations()
      log.debug(s"random operations on [${myself.name}]: ${operations.mkString(", ")}")
      try {
        // perform random operations with both delta and full-state replicators
        // and compare that the end result is the same

        for (op ← operations) {
          log.debug("operation: {}", op)
          op match {
            case Delay(d) ⇒ Thread.sleep(d)
            case Incr(key, n, consistency) ⇒
              fullStateReplicator ! Update(key, PNCounter.empty, consistency)(_ + n)
              deltaReplicator ! Update(key, PNCounter.empty, WriteLocal)(_ + n)
            case Decr(key, n, consistency) ⇒
              fullStateReplicator ! Update(key, PNCounter.empty, consistency)(_ - n)
              deltaReplicator ! Update(key, PNCounter.empty, WriteLocal)(_ - n)
          }
        }

        enterBarrier("updated-2")

        List(KeyA, KeyB, KeyC).foreach { key ⇒
          within(5.seconds) {
            awaitAssert {
              val p = TestProbe()
              fullStateReplicator.tell(Get(key, ReadLocal), p.ref)
              val fullStateValue = p.expectMsgType[GetSuccess[PNCounter]].dataValue
              deltaReplicator.tell(Get(key, ReadLocal), p.ref)
              val deltaValue = p.expectMsgType[GetSuccess[PNCounter]].dataValue
              deltaValue should ===(fullStateValue)
            }
          }
        }

        enterBarrierAfterTestStep()
      } catch {
        case e: Throwable ⇒
          info(s"random operations on [${myself.name}]: ${operations.mkString(", ")}")
          throw e
      }
    }
  }

}

