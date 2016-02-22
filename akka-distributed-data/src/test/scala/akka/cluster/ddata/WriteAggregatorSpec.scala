/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.ddata

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.Props
import akka.testkit._
import akka.actor.Address
import akka.actor.ActorRef
import akka.cluster.ddata.Replicator.Internal._
import akka.cluster.ddata.Replicator._
import akka.actor.ActorSelection

object WriteAggregatorSpec {

  val key = GSetKey[String]("a")

  def writeAggregatorProps(data: GSet[String], consistency: Replicator.WriteConsistency,
                           probes: Map[Address, ActorRef], nodes: Set[Address], replyTo: ActorRef): Props =
    Props(new TestWriteAggregator(data, consistency, probes, nodes, replyTo))

  class TestWriteAggregator(data: GSet[String], consistency: Replicator.WriteConsistency,
                            probes: Map[Address, ActorRef], nodes: Set[Address], replyTo: ActorRef)
    extends WriteAggregator(key, DataEnvelope(data), consistency, None, nodes, replyTo) {

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
      case msg ⇒
        replicator = Some(sender())
        replica ! msg
    }
  }
}

class WriteAggregatorSpec extends AkkaSpec("""
      akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      akka.remote.netty.tcp.port=0
      """)
  with ImplicitSender {

  val nodeA = Address("akka.tcp", "Sys", "a", 2552)
  val nodeB = nodeA.copy(host = Some("b"))
  val nodeC = nodeA.copy(host = Some("c"))
  val nodeD = nodeA.copy(host = Some("d"))
  // 4 replicas + the local => 5
  val nodes = Set(nodeA, nodeB, nodeC, nodeD)

  val data = GSet.empty + "A" + "B"
  val timeout = 3.seconds.dilated
  val writeTwo = WriteTo(2, timeout)
  val writeMajority = WriteMajority(timeout)

  def probes(probe: ActorRef): Map[Address, ActorRef] =
    nodes.toSeq.map(_ -> system.actorOf(WriteAggregatorSpec.writeAckAdapterProps(probe))).toMap

  "WriteAggregator" must {
    "send to at least N/2+1 replicas when WriteMajority" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeMajority, probes(probe.ref), nodes, testActor))

      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      expectMsg(UpdateSuccess(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "send to more when no immediate reply" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeMajority, probes(probe.ref), nodes, testActor))

      probe.expectMsgType[Write]
      // no reply
      probe.expectMsgType[Write]
      // no reply
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      probe.expectMsgType[Write]
      probe.lastSender ! WriteAck
      expectMsg(UpdateSuccess(WriteAggregatorSpec.key, None))
      watch(aggr)
      expectTerminated(aggr)
    }

    "timeout when less than required acks" in {
      val probe = TestProbe()
      val aggr = system.actorOf(WriteAggregatorSpec.writeAggregatorProps(
        data, writeMajority, probes(probe.ref), nodes, testActor))

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
}
