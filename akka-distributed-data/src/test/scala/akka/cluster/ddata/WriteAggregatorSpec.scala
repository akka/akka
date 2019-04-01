/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
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

import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object WriteAggregatorSpec {

  val KeyA = GSetKey[String]("A")
  val KeyB = ORSetKey[String]("B")

  def writeAggregatorProps(
      data: GSet[String],
      consistency: Replicator.WriteConsistency,
      probes: Map[UniqueAddress, ActorRef],
      selfUniqueAddress: UniqueAddress,
      nodes: Set[UniqueAddress],
      unreachable: Set[UniqueAddress],
      replyTo: ActorRef,
      durable: Boolean): Props =
    Props(
      new TestWriteAggregator(
        KeyA,
        data,
        None,
        consistency,
        probes,
        selfUniqueAddress,
        nodes,
        unreachable,
        replyTo,
        durable))

  def writeAggregatorPropsWithDelta(
      data: ORSet[String],
      delta: Delta,
      consistency: Replicator.WriteConsistency,
      probes: Map[UniqueAddress, ActorRef],
      selfUniqueAddress: UniqueAddress,
      nodes: Set[UniqueAddress],
      unreachable: Set[UniqueAddress],
      replyTo: ActorRef,
      durable: Boolean): Props =
    Props(
      new TestWriteAggregator(
        KeyB,
        data,
        Some(delta),
        consistency,
        probes,
        selfUniqueAddress,
        nodes,
        unreachable,
        replyTo,
        durable))

  class TestWriteAggregator(
      key: Key.KeyR,
      data: ReplicatedData,
      delta: Option[Delta],
      consistency: Replicator.WriteConsistency,
      probes: Map[UniqueAddress, ActorRef],
      selfUniqueAddress: UniqueAddress,
      nodes: Set[UniqueAddress],
      unreachable: Set[UniqueAddress],
      replyTo: ActorRef,
      durable: Boolean)
      extends WriteAggregator(
        key,
        DataEnvelope(data),
        delta,
        consistency,
        None,
        selfUniqueAddress,
        nodes,
        unreachable,
        replyTo,
        durable) {

    override def replica(address: UniqueAddress): ActorSelection =
      context.actorSelection(probes(address).path)

    override def senderAddress(): Address =
      probes.find { case (_, r) => r == sender() }.get._1.address
  }

  def writeAckAdapterProps(replica: ActorRef): Props =
    Props(new WriteAckAdapter(replica))

  class WriteAckAdapter(replica: ActorRef) extends Actor {
    var replicator: Option[ActorRef] = None

    def receive = {
      case WriteAck =>
        replicator.foreach(_ ! WriteAck)
      case WriteNack =>
        replicator.foreach(_ ! WriteNack)
      case DeltaNack =>
        replicator.foreach(_ ! DeltaNack)
      case msg =>
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
      """) with ImplicitSender {
  import WriteAggregatorSpec._

  val protocol =
    if (RARP(system).provider.remoteSettings.Artery.Enabled) "akka"
    else "akka.tcp"

  val nodeA = UniqueAddress(Address(protocol, "Sys", "a", 2552), 17L)
  val nodeB = UniqueAddress(Address(protocol, "Sys", "b", 2552), 17L)
  val nodeC = UniqueAddress(Address(protocol, "Sys", "c", 2552), 17L)
  val nodeD = UniqueAddress(Address(protocol, "Sys", "d", 2552), 17L)
  // 4 replicas + the local => 5
  val nodes = Set(nodeA, nodeB, nodeC, nodeD)

  val data = GSet.empty + "A" + "B"
  val timeout = 3.seconds.dilated
  val writeThree = WriteTo(3, timeout)
  val writeMajority = WriteMajority(timeout)
  val writeAll = WriteAll(timeout)

  val selfUniqueAddress: UniqueAddress = Cluster(system).selfUniqueAddress

  def probes(probe: ActorRef): Map[UniqueAddress, ActorRef] =
    nodes.toSeq.map(_ -> system.actorOf(WriteAggregatorSpec.writeAckAdapterProps(probe))).toMap

  /**
   * Create a tuple for each node with the WriteAckAdapter and the TestProbe
   */
  def probes(): Map[UniqueAddress, TestMock] = {
    nodes.toSeq.map(_ -> TestMock()).toMap
  }

  "WriteAggregator" must {
    "send to at least N/2+1 replicas when WriteMajority" in {
      val probe = TestProbe()
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorProps(
          data,
          writeMajority,
          probes(probe.ref),
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = false))

      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      expectMsg(UpdateSuccess(WriteAggregatorSpec.KeyA, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "send to more when no immediate reply" in {
      val testProbes = probes()
      val testProbeRefs = testProbes.map { case (a, tm) => a -> tm.writeAckAdapter }
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorProps(
          data,
          writeMajority,
          testProbeRefs,
          selfUniqueAddress,
          nodes,
          Set(nodeC, nodeD),
          testActor,
          durable = false))

      testProbes(nodeA).expectMsgType[Write]
      // no reply
      testProbes(nodeB).expectMsgType[Write]
      testProbes(nodeB).lastSender ! WriteAck
      // Make sure that unreachable nodes do not get a message until 1/5 of the time the reachable nodes did not answer
      val t = timeout / 5 - 50.milliseconds.dilated
      import system.dispatcher
      Future.sequence {
        Seq(Future { testProbes(nodeC).expectNoMessage(t) }, Future { testProbes(nodeD).expectNoMessage(t) })
      }.futureValue
      testProbes(nodeC).expectMsgType[Write]
      testProbes(nodeC).lastSender ! WriteAck
      testProbes(nodeD).expectMsgType[Write]
      testProbes(nodeD).lastSender ! WriteAck

      expectMsg(UpdateSuccess(WriteAggregatorSpec.KeyA, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "timeout when less than required acks" in {
      val probe = TestProbe()
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorProps(
          data,
          writeMajority,
          probes(probe.ref),
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = false))

      probe.expectMsgType[Write]
      // no reply
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      // no reply
      probe.expectMsgType[Write]
      // no reply
      expectMsg(UpdateTimeout(WriteAggregatorSpec.KeyA, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "calculate majority with minCap" in {
      val minCap = 5

      import ReadWriteAggregator._

      calculateMajorityWithMinCap(minCap, 3) should be(3)
      calculateMajorityWithMinCap(minCap, 4) should be(4)
      calculateMajorityWithMinCap(minCap, 5) should be(5)
      calculateMajorityWithMinCap(minCap, 6) should be(5)
      calculateMajorityWithMinCap(minCap, 7) should be(5)
      calculateMajorityWithMinCap(minCap, 8) should be(5)
      calculateMajorityWithMinCap(minCap, 9) should be(5)
      calculateMajorityWithMinCap(minCap, 10) should be(6)
      calculateMajorityWithMinCap(minCap, 11) should be(6)
      calculateMajorityWithMinCap(minCap, 12) should be(7)
    }
  }

  "WriteAggregator with delta" must {
    implicit val node = DistributedData(system).selfUniqueAddress
    val fullState1 = ORSet.empty[String] :+ "a" :+ "b"
    val fullState2 = fullState1.resetDelta :+ "c"
    val delta = Delta(DataEnvelope(fullState2.delta.get), 2L, 2L)

    "send deltas first" in {
      val probe = TestProbe()
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorPropsWithDelta(
          fullState2,
          delta,
          writeMajority,
          probes(probe.ref),
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = false))

      probe.expectMsgType[DeltaPropagation]
      probe.lastSender ! WriteAck
      probe.expectMsgType[DeltaPropagation]
      probe.lastSender ! WriteAck
      expectMsg(UpdateSuccess(WriteAggregatorSpec.KeyB, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "retry with full state when no immediate reply or nack" in {
      val testProbes = probes()
      val testProbeRefs = testProbes.map { case (a, tm) => a -> tm.writeAckAdapter }
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorPropsWithDelta(
          fullState2,
          delta,
          writeAll,
          testProbeRefs,
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = false))

      testProbes(nodeA).expectMsgType[DeltaPropagation]
      // no reply
      testProbes(nodeB).expectMsgType[DeltaPropagation]
      testProbes(nodeB).lastSender ! WriteAck
      testProbes(nodeC).expectMsgType[DeltaPropagation]
      testProbes(nodeC).lastSender ! WriteAck
      testProbes(nodeD).expectMsgType[DeltaPropagation]
      testProbes(nodeD).lastSender ! DeltaNack

      // here is the second round
      testProbes(nodeA).expectMsgType[Write]
      testProbes(nodeA).lastSender ! WriteAck
      testProbes(nodeD).expectMsgType[Write]
      testProbes(nodeD).lastSender ! WriteAck
      testProbes(nodeB).expectNoMessage(100.millis)
      testProbes(nodeC).expectNoMessage(100.millis)

      expectMsg(UpdateSuccess(WriteAggregatorSpec.KeyB, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "timeout when less than required acks" in {
      val probe = TestProbe()
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorPropsWithDelta(
          fullState2,
          delta,
          writeAll,
          probes(probe.ref),
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = false))

      probe.expectMsgType[DeltaPropagation]
      // no reply
      probe.expectMsgType[DeltaPropagation]
      probe.lastSender ! WriteAck
      probe.expectMsgType[DeltaPropagation]
      // nack
      probe.lastSender ! DeltaNack
      probe.expectMsgType[DeltaPropagation]
      // no reply

      // only 1 ack so we expect 3 full state Write
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.expectMsgType[Write]

      // still not enough acks
      expectMsg(UpdateTimeout(WriteAggregatorSpec.KeyB, None))
      watch(aggr)
      expectTerminated(aggr)
    }
  }

  "Durable WriteAggregator" must {
    "not reply before local confirmation" in {
      val probe = TestProbe()
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorProps(
          data,
          writeThree,
          probes(probe.ref),
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = true))

      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      expectNoMessage(200.millis)

      // the local write
      aggr ! UpdateSuccess(WriteAggregatorSpec.KeyA, None)

      expectMsg(UpdateSuccess(WriteAggregatorSpec.KeyA, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "tolerate WriteNack if enough WriteAck" in {
      val probe = TestProbe()
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorProps(
          data,
          writeThree,
          probes(probe.ref),
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = true))

      aggr ! UpdateSuccess(WriteAggregatorSpec.KeyA, None) // the local write
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck

      expectMsg(UpdateSuccess(WriteAggregatorSpec.KeyA, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "reply with StoreFailure when too many nacks" in {
      val probe = TestProbe()
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorProps(
          data,
          writeMajority,
          probes(probe.ref),
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = true))

      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack
      aggr ! UpdateSuccess(WriteAggregatorSpec.KeyA, None) // the local write
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack

      expectMsg(StoreFailure(WriteAggregatorSpec.KeyA, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "timeout when less than required acks" in {
      val probe = TestProbe()
      val aggr = system.actorOf(
        WriteAggregatorSpec.writeAggregatorProps(
          data,
          writeMajority,
          probes(probe.ref),
          selfUniqueAddress,
          nodes,
          Set.empty,
          testActor,
          durable = true))

      probe.expectMsgType[Write]
      // no reply
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack
      probe.expectMsgType[Write]
      probe.lastSender ! WriteNack

      expectMsg(UpdateTimeout(WriteAggregatorSpec.KeyA, None))
      watch(aggr)
      expectTerminated(aggr)
    }
  }

}
