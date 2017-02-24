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
    akka.loglevel = DEBUG
    akka.actor.provider = "cluster"
    akka.log-dead-letters-during-shutdown = off
    """))

  testTransport(on = true)

  sealed trait Op
  final case class Delay(n: Int) extends Op
  final case class Incr(key: PNCounterKey, n: Int, consistency: WriteConsistency) extends Op
  final case class Decr(key: PNCounterKey, n: Int, consistency: WriteConsistency) extends Op
  final case class Add(key: ORSetKey[String], elem: String, consistency: WriteConsistency) extends Op
  final case class Remove(key: ORSetKey[String], elem: String, consistency: WriteConsistency) extends Op

  val timeout = 5.seconds
  val writeTwo = WriteTo(2, timeout)
  val writeMajority = WriteMajority(timeout)

  val KeyA = PNCounterKey("A")
  val KeyB = PNCounterKey("B")
  val KeyC = PNCounterKey("C")
  val KeyD = ORSetKey[String]("D")
  val KeyE = ORSetKey[String]("E")
  val KeyF = ORSetKey[String]("F")

  def generateOperations(onNode: RoleName): Vector[Op] = {
    val rnd = ThreadLocalRandom.current()

    def consistency(): WriteConsistency = {
      rnd.nextInt(100) match {
        case n if n < 90  ⇒ WriteLocal
        case n if n < 95  ⇒ writeTwo
        case n if n < 100 ⇒ writeMajority
      }
    }

    def rndPnCounterkey(): PNCounterKey = {
      rnd.nextInt(3) match {
        case 0 ⇒ KeyA
        case 1 ⇒ KeyB
        case 2 ⇒ KeyC
      }
    }

    def rndOrSetkey(): ORSetKey[String] = {
      rnd.nextInt(3) match {
        case 0 ⇒ KeyD
        case 1 ⇒ KeyE
        case 2 ⇒ KeyF
      }
    }

    var availableForRemove = Set.empty[String]

    def rndAddElement(): String = {
      // lower case a - j
      val s = (97 + rnd.nextInt(10)).toChar.toString
      availableForRemove += s
      s
    }

    def rndRemoveElement(): String = {
      if (availableForRemove.isEmpty)
        "a"
      else
        availableForRemove.toVector(rnd.nextInt(availableForRemove.size))
    }

    (0 to (30 + rnd.nextInt(10))).map { _ ⇒
      rnd.nextInt(4) match {
        case 0 ⇒ Delay(rnd.nextInt(500))
        case 1 ⇒ Incr(rndPnCounterkey(), rnd.nextInt(100), consistency())
        case 2 ⇒ Decr(rndPnCounterkey(), rnd.nextInt(10), consistency())
        case 3 ⇒
          // ORSet
          val key = rndOrSetkey()
          // only removals for KeyF on node first
          if (key == KeyF && onNode == first && rnd.nextBoolean())
            Remove(key, rndRemoveElement(), consistency())
          else
            Add(key, rndAddElement(), consistency())
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
        // by setting something for each key we don't have to worry about NotFound
        List(KeyA, KeyB, KeyC).foreach { key ⇒
          fullStateReplicator ! Update(key, PNCounter.empty, WriteLocal)(_ + 1)
          deltaReplicator ! Update(key, PNCounter.empty, WriteLocal)(_ + 1)
        }
        List(KeyD, KeyE, KeyF).foreach { key ⇒
          fullStateReplicator ! Update(key, ORSet.empty[String], WriteLocal)(_ + "a")
          deltaReplicator ! Update(key, ORSet.empty[String], WriteLocal)(_ + "a")
        }
      }
      enterBarrier("updated-1")

      within(5.seconds) {
        awaitAssert {
          val p = TestProbe()
          List(KeyA, KeyB, KeyC).foreach { key ⇒
            fullStateReplicator.tell(Get(key, ReadLocal), p.ref)
            p.expectMsgType[GetSuccess[PNCounter]].dataValue.getValue.intValue should be(1)
          }
        }
        awaitAssert {
          val p = TestProbe()
          List(KeyD, KeyE, KeyF).foreach { key ⇒
            fullStateReplicator.tell(Get(key, ReadLocal), p.ref)
            p.expectMsgType[GetSuccess[ORSet[String]]].dataValue.elements should ===(Set("a"))
          }
        }
      }

      enterBarrierAfterTestStep()
    }

    "be eventually consistent" in {
      val operations = generateOperations(onNode = myself)
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
              deltaReplicator ! Update(key, PNCounter.empty, consistency)(_ + n)
            case Decr(key, n, consistency) ⇒
              fullStateReplicator ! Update(key, PNCounter.empty, consistency)(_ - n)
              deltaReplicator ! Update(key, PNCounter.empty, consistency)(_ - n)
            case Add(key, elem, consistency) ⇒
              // to have an deterministic result when mixing add/remove we can only perform
              // the ORSet operations from one node
              runOn((if (key == KeyF) List(first) else List(first, second, third)): _*) {
                fullStateReplicator ! Update(key, ORSet.empty[String], consistency)(_ + elem)
                deltaReplicator ! Update(key, ORSet.empty[String], consistency)(_ + elem)
              }
            case Remove(key, elem, consistency) ⇒
              runOn(first) {
                fullStateReplicator ! Update(key, ORSet.empty[String], consistency)(_ - elem)
                deltaReplicator ! Update(key, ORSet.empty[String], consistency)(_ - elem)
              }
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

        List(KeyD, KeyE, KeyF).foreach { key ⇒
          within(5.seconds) {
            awaitAssert {
              val p = TestProbe()
              fullStateReplicator.tell(Get(key, ReadLocal), p.ref)
              val fullStateValue = p.expectMsgType[GetSuccess[ORSet[String]]].dataValue.elements
              deltaReplicator.tell(Get(key, ReadLocal), p.ref)
              val deltaValue = p.expectMsgType[GetSuccess[ORSet[String]]].dataValue.elements
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

