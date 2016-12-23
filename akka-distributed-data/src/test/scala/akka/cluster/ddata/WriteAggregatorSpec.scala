/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.ddata

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.Props
import akka.testkit._
import akka.cluster.ddata.Replicator.Internal._
import akka.cluster.ddata.Replicator._
import akka.remote.RARP

import scala.concurrent.Future

object WriteAggregatorSpec {

  val key = GSetKey[String]("a")

  def writeAggregatorProps(data: GSet[String], consistency: Replicator.WriteConsistency,
                           probes: Map[Address, ActorRef], nodes: Set[Address], unreachable: Set[Address], replyTo: ActorRef, durable: Boolean): Props =
    Props(new TestWriteAggregator(data, consistency, probes, nodes, unreachable, replyTo, durable))

  class TestWriteAggregator(data: GSet[String], consistency: Replicator.WriteConsistency,
                            probes: Map[Address, ActorRef], nodes: Set[Address], unreachable: Set[Address], replyTo: ActorRef, durable: Boolean)
    extends WriteAggregator(key, DataEnvelope(data), consistency, None, nodes, unreachable, replyTo, durable) {

    override def replica(address: Address): ActorSelection =
      context.actorSelection(probes(address).path)

    override def senderAddress(): Address =
      probes.find { case (a, r) ⇒ r == sender() }.get._1
  }

  def writeAckAdapterProps(replica: ActorRef): Props =
    Props(new WriteAckAdapter(replica))

  class WriteAckAdapter(replica: ActorRef) extends Actor {
    var replicator: Option[ActorRef] = None

    def receive = {
      case WriteAck ⇒
        replicator.foreach(_ ! WriteAck)
      case WriteNack ⇒
        replicator.foreach(_ ! WriteNack)
      case msg ⇒
        replicator = Some(sender())
        replica ! msg
    }
  }

  object TestMock {
    def apply()(implicit system: ActorSystem) = new TestMock(system)
  }
  class TestMock(_application: ActorSystem) extends TestProbe(_application) {
    val writeAckAdapter = system.actorOf(WriteAggregatorSpec.writeAckAdapterProps(this.ref))
  }
}

class WriteAggregatorSpec extends AkkaSpec(s"""
      akka.actor.provider = "cluster"
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.cluster.distributed-data.durable.lmdb {
        dir = target/WriteAggregatorSpec-${System.currentTimeMillis}-ddata
        map-size = 10 MiB
      }
      """)
  with ImplicitSender {
  import WriteAggregatorSpec._

  val protocol =
    if (RARP(system).provider.remoteSettings.Artery.Enabled) "akka"
    else "akka.tcp"

  val nodeA = Address(protocol, "Sys", "a", 2552)
  val nodeB = nodeA.copy(host = Some("b"))
  val nodeC = nodeA.copy(host = Some("c"))
  val nodeD = nodeA.copy(host = Some("d"))
  // 4 replicas + the local => 5
  val nodes = Set(nodeA, nodeB, nodeC, nodeD)

  val data = GSet.empty + "A" + "B"
  val timeout = 3.seconds.dilated
  val writeThree = WriteTo(3, timeout)
  val writeMajority = WriteMajority(timeout)

  def probes(probe: ActorRef): Map[Address, ActorRef] =
    nodes.toSeq.map(_ → system.actorOf(WriteAggregatorSpec.writeAckAdapterProps(probe))).toMap

  /**
   * Create a tuple for each node with the WriteAckAdapter and the TestProbe
   */
  def probes(): Map[Address, TestMock] = {
    val probe = TestProbe()
    nodes.toSeq.map(_ → TestMock()).toMap
  }

  "WriteAggregator" must {
    "send to at least N/2+1 replicas when WriteMajority" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeMajority, probes(probe.ref), nodes, Set.empty, testActor, durable = false))

      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      expectMsg(UpdateSuccess(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "send to more when no immediate reply" in {
      val testProbes = probes()
      val testProbeRefs = testProbes.map { case (a, tm) ⇒ a → tm.writeAckAdapter }
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeMajority, testProbeRefs, nodes, Set(nodeC, nodeD), testActor, durable = false))

      testProbes(nodeA).expectMsgType[Write]
      // no reply
      testProbes(nodeB).expectMsgType[Write]
      testProbes(nodeB).lastSender ! WriteAck
      // Make sure that unreachable nodes do not get a message until 1/5 of the time the reachable nodes did not answer
      val t = timeout / 5 - 50.milliseconds.dilated
      import system.dispatcher
      Future.sequence {
        Seq(
          Future { testProbes(nodeC).expectNoMsg(t) },
          Future { testProbes(nodeD).expectNoMsg(t) }
        )
      }.futureValue
      testProbes(nodeC).expectMsgType[Write]
      testProbes(nodeC).lastSender ! WriteAck
      testProbes(nodeD).expectMsgType[Write]
      testProbes(nodeD).lastSender ! WriteAck

      expectMsg(UpdateSuccess(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "timeout when less than required acks" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeMajority, probes(probe.ref), nodes, Set.empty, testActor, durable = false))

      probe.expectMsgType[Write]
      // no reply
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      // no reply
      probe.expectMsgType[Write]
      // no reply
      expectMsg(UpdateTimeout(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }
  }

  "Durable WriteAggregator" must {
    "not reply before local confirmation" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeThree, probes(probe.ref), nodes, Set.empty, testActor, durable = true))

      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      expectNoMsg(200.millis)

      // the local write
      aggr ! UpdateSuccess(WriteAggregatorSpec.key, None)

      expectMsg(UpdateSuccess(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "tolerate WriteNack if enough WriteAck" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeThree, probes(probe.ref), nodes, Set.empty, testActor, durable = true))

      aggr ! UpdateSuccess(WriteAggregatorSpec.key, None) // the local write
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck

      expectMsg(UpdateSuccess(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "reply with StoreFailure when too many nacks" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeMajority, probes(probe.ref), nodes, Set.empty, testActor, durable = true))

      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack
      aggr ! UpdateSuccess(WriteAggregatorSpec.key, None) // the local write
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack

      expectMsg(StoreFailure(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "timeout when less than required acks" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeMajority, probes(probe.ref), nodes, Set.empty, testActor, durable = true))

      probe.expectMsgType[Write]
      // no reply
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack

      expectMsg(UpdateTimeout(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }
  }

}
