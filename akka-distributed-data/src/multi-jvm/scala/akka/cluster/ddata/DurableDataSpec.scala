/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.cluster.Cluster
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

final case class DurableDataSpecConfig(writeBehind: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = DEBUG
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.log-dead-letters-during-shutdown = off
    akka.cluster.distributed-data.durable.keys = ["durable*"]
    akka.cluster.distributed-data.durable.lmdb {
      dir = target/DurableDataSpec-${System.currentTimeMillis}-ddata
      map-size = 10 MiB
      write-behind-interval = ${if (writeBehind) "200ms" else "off"}
    }
    akka.test.single-expect-default = 5s
    """))
}

object DurableDataSpec {
  def testDurableStoreProps(failLoad: Boolean = false, failStore: Boolean = false): Props =
    Props(new TestDurableStore(failLoad, failStore))

  class TestDurableStore(failLoad: Boolean, failStore: Boolean) extends Actor {
    import DurableStore._
    def receive = {
      case LoadAll ⇒
        if (failLoad)
          throw new LoadFailed("failed to load durable distributed-data") with NoStackTrace
        else
          sender() ! LoadAllCompleted

      case Store(key, data, reply) ⇒
        if (failStore) reply match {
          case Some(StoreReply(_, failureMsg, replyTo)) ⇒ replyTo ! failureMsg
          case None                                     ⇒
        }
        else reply match {
          case Some(StoreReply(successMsg, _, replyTo)) ⇒ replyTo ! successMsg
          case None                                     ⇒
        }
    }

  }

}

class DurableDataSpecMultiJvmNode1 extends DurableDataSpec(DurableDataSpecConfig(writeBehind = false))
class DurableDataSpecMultiJvmNode2 extends DurableDataSpec(DurableDataSpecConfig(writeBehind = false))

class DurableDataWriteBehindSpecMultiJvmNode1 extends DurableDataSpec(DurableDataSpecConfig(writeBehind = true))
class DurableDataWriteBehindSpecMultiJvmNode2 extends DurableDataSpec(DurableDataSpecConfig(writeBehind = true))

abstract class DurableDataSpec(multiNodeConfig: DurableDataSpecConfig)
  extends MultiNodeSpec(multiNodeConfig) with STMultiNodeSpec with ImplicitSender {
  import DurableDataSpec._
  import Replicator._
  import multiNodeConfig._

  override def initialParticipants = roles.size

  implicit val cluster = Cluster(system)

  val timeout = 5.seconds.dilated
  val writeTwo = WriteTo(2, timeout)
  val readTwo = ReadFrom(2, timeout)

  val KeyA = GCounterKey("durable-A")
  val KeyB = GCounterKey("durable-B")
  val KeyC = ORSetKey[String]("durable-C")

  var testStepCounter = 0
  def enterBarrierAfterTestStep(): Unit = {
    testStepCounter += 1
    enterBarrier("after-" + testStepCounter)
  }

  def newReplicator(sys: ActorSystem = system) = sys.actorOf(Replicator.props(
    ReplicatorSettings(system).withGossipInterval(1.second)), "replicator-" + testStepCounter)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Durable CRDT" must {

    "work in single node cluster" in {
      join(first, first)

      runOn(first) {

        val r = newReplicator()
        within(5.seconds) {
          awaitAssert {
            r ! GetReplicaCount
            expectMsg(ReplicaCount(1))
          }
        }

        r ! Get(KeyA, ReadLocal)
        expectMsg(NotFound(KeyA, None))

        r ! Update(KeyA, GCounter(), WriteLocal)(_ + 1)
        r ! Update(KeyA, GCounter(), WriteLocal)(_ + 1)
        r ! Update(KeyA, GCounter(), WriteLocal)(_ + 1)

        expectMsg(UpdateSuccess(KeyA, None))
        expectMsg(UpdateSuccess(KeyA, None))
        expectMsg(UpdateSuccess(KeyA, None))

        watch(r)
        system.stop(r)
        expectTerminated(r)

        var r2: ActorRef = null
        awaitAssert(r2 = newReplicator()) // try until name is free

        // wait until all loaded
        awaitAssert {
          r2 ! GetKeyIds
          expectMsgType[GetKeyIdsResult].keyIds should !==(Set.empty[String])
        }
        r2 ! Get(KeyA, ReadLocal)
        expectMsgType[GetSuccess[GCounter]].dataValue.value.toInt should be(3)

        watch(r2)
        system.stop(r2)
        expectTerminated(r2)
      }

      enterBarrierAfterTestStep()
    }
  }

  "work in multi node cluster" in {
    join(second, first)

    val r = newReplicator()
    within(5.seconds) {
      awaitAssert {
        r ! GetReplicaCount
        expectMsg(ReplicaCount(2))
      }
    }
    enterBarrier("both-initalized")

    r ! Update(KeyA, GCounter(), writeTwo)(_ + 1)
    expectMsg(UpdateSuccess(KeyA, None))

    r ! Update(KeyC, ORSet.empty[String], writeTwo)(_ + myself.name)
    expectMsg(UpdateSuccess(KeyC, None))

    enterBarrier("update-done-" + testStepCounter)

    r ! Get(KeyA, readTwo)
    expectMsgType[GetSuccess[GCounter]].dataValue.value.toInt should be(2)

    r ! Get(KeyC, readTwo)
    expectMsgType[GetSuccess[ORSet[String]]].dataValue.elements should be(Set(first.name, second.name))

    enterBarrier("values-verified-" + testStepCounter)

    watch(r)
    system.stop(r)
    expectTerminated(r)

    var r2: ActorRef = null
    awaitAssert(r2 = newReplicator()) // try until name is free
    awaitAssert {
      r2 ! GetKeyIds
      expectMsgType[GetKeyIdsResult].keyIds should !==(Set.empty[String])
    }

    r2 ! Get(KeyA, ReadLocal)
    expectMsgType[GetSuccess[GCounter]].dataValue.value.toInt should be(2)

    r2 ! Get(KeyC, ReadLocal)
    expectMsgType[GetSuccess[ORSet[String]]].dataValue.elements should be(Set(first.name, second.name))

    enterBarrierAfterTestStep()
  }

  "be durable after gossip update" in {
    val r = newReplicator()

    runOn(first) {
      r ! Update(KeyC, ORSet.empty[String], WriteLocal)(_ + myself.name)
      expectMsg(UpdateSuccess(KeyC, None))
    }

    runOn(second) {
      r ! Subscribe(KeyC, testActor)
      expectMsgType[Changed[ORSet[String]]].dataValue.elements should be(Set(first.name))

      // must do one more roundtrip to be sure that it keyB is stored, since Changed might have
      // been sent out before storage
      r ! Update(KeyA, GCounter(), WriteLocal)(_ + 1)
      expectMsg(UpdateSuccess(KeyA, None))

      watch(r)
      system.stop(r)
      expectTerminated(r)

      var r2: ActorRef = null
      awaitAssert(r2 = newReplicator()) // try until name is free
      awaitAssert {
        r2 ! GetKeyIds
        expectMsgType[GetKeyIdsResult].keyIds should !==(Set.empty[String])
      }

      r2 ! Get(KeyC, ReadLocal)
      expectMsgType[GetSuccess[ORSet[String]]].dataValue.elements should be(Set(first.name))
    }

    enterBarrierAfterTestStep()
  }

  "handle Update before load" in {
    runOn(first) {

      val sys1 = ActorSystem("AdditionalSys", system.settings.config)
      val addr = Cluster(sys1).selfAddress
      try {
        Cluster(sys1).join(addr)
        new TestKit(sys1) with ImplicitSender {

          val r = newReplicator(sys1)
          within(5.seconds) {
            awaitAssert {
              r ! GetReplicaCount
              expectMsg(ReplicaCount(1))
            }
          }

          r ! Get(KeyA, ReadLocal)
          expectMsg(NotFound(KeyA, None))

          r ! Update(KeyA, GCounter(), WriteLocal)(_ + 1)
          r ! Update(KeyA, GCounter(), WriteLocal)(_ + 1)
          r ! Update(KeyA, GCounter(), WriteLocal)(_ + 1)
          r ! Update(KeyB, GCounter(), WriteLocal)(_ + 1)

          expectMsg(UpdateSuccess(KeyA, None))
          expectMsg(UpdateSuccess(KeyA, None))
          expectMsg(UpdateSuccess(KeyA, None))
          expectMsg(UpdateSuccess(KeyB, None))

          watch(r)
          system.stop(r)
          expectTerminated(r)
        }
      } finally {
        Await.ready(sys1.terminate(), 10.seconds)
      }

      val sys2 = ActorSystem(
        "AdditionalSys",
        // use the same port
        ConfigFactory.parseString(s"""
            akka.remote.artery.canonical.port = ${addr.port.get}
            akka.remote.netty.tcp.port = ${addr.port.get}
            """).withFallback(system.settings.config))
      try {
        Cluster(sys2).join(addr)
        new TestKit(sys2) with ImplicitSender {

          val r2: ActorRef = newReplicator(sys2)

          // it should be possible to update while loading is in progress
          r2 ! Update(KeyB, GCounter(), WriteLocal)(_ + 1)
          expectMsg(UpdateSuccess(KeyB, None))

          // wait until all loaded
          awaitAssert {
            r2 ! GetKeyIds
            expectMsgType[GetKeyIdsResult].keyIds should ===(Set(KeyA.id, KeyB.id))
          }
          r2 ! Get(KeyA, ReadLocal)
          expectMsgType[GetSuccess[GCounter]].dataValue.value.toInt should be(3)
          r2 ! Get(KeyB, ReadLocal)
          expectMsgType[GetSuccess[GCounter]].dataValue.value.toInt should be(2)
        }
      } finally {
        Await.ready(sys1.terminate(), 10.seconds)
      }

    }
    enterBarrierAfterTestStep()
  }

  "stop Replicator if Load fails" in {
    runOn(first) {
      val r = system.actorOf(
        Replicator.props(
          ReplicatorSettings(system).withDurableStoreProps(testDurableStoreProps(failLoad = true))),
        "replicator-" + testStepCounter)
      watch(r)
      expectTerminated(r)
    }
    enterBarrierAfterTestStep()
  }

  "reply with StoreFailure if store fails" in {
    runOn(first) {
      val r = system.actorOf(
        Replicator.props(
          ReplicatorSettings(system).withDurableStoreProps(testDurableStoreProps(failStore = true))),
        "replicator-" + testStepCounter)
      r ! Update(KeyA, GCounter(), WriteLocal, request = Some("a"))(_ + 1)
      expectMsg(StoreFailure(KeyA, Some("a")))
    }
    enterBarrierAfterTestStep()
  }

}

