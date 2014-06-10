/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication.protobuf

import scala.concurrent.duration._
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Props
import akka.cluster.UniqueAddress
import akka.contrib.datareplication.GSet
import akka.contrib.datareplication.PruningState
import akka.contrib.datareplication.PruningState.PruningInitialized
import akka.contrib.datareplication.PruningState.PruningPerformed
import akka.contrib.datareplication.Replicator._
import akka.contrib.datareplication.Replicator.Internal._
import akka.testkit.AkkaSpec
import akka.util.ByteString

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ReplicatorMessageSerializerSpec extends AkkaSpec {

  val serializer = new ReplicatorMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  val address1 = UniqueAddress(Address("akka.tcp", "system", "some.host.org", 4711), 1)
  val address2 = UniqueAddress(Address("akka.tcp", "system", "other.host.org", 4711), 2)
  val address3 = UniqueAddress(Address("akka.tcp", "system", "some.host.org", 4712), 3)

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, obj.getClass)
    ref should be(obj)
  }

  "ReplicatorMessageSerializer" must {

    "serialize Replicator messages" in {
      val ref1 = system.actorOf(Props.empty, "ref1")
      val data1 = GSet() :+ "a"

      checkSerialization(Get("A"))
      checkSerialization(Get("A", ReadQuorum, 2.seconds, Some("x")))
      checkSerialization(GetSuccess("A", data1, 17, None))
      checkSerialization(GetSuccess("A", data1, 17, Some("x")))
      checkSerialization(NotFound("A", Some("x")))
      checkSerialization(GetFailure("A", Some("x")))
      checkSerialization(Subscribe("A", ref1))
      checkSerialization(Unsubscribe("A", ref1))
      checkSerialization(Changed("A", data1))
      checkSerialization(DataEnvelope(data1))
      checkSerialization(DataEnvelope(data1, pruning = Map(
        address1 -> PruningState(address2, PruningPerformed),
        address3 -> PruningState(address2, PruningInitialized(Set(address1.address))))))
      checkSerialization(Write("A", DataEnvelope(data1)))
      checkSerialization(WriteAck)
      checkSerialization(Read("A"))
      checkSerialization(ReadResult(Some(DataEnvelope(data1))))
      checkSerialization(ReadResult(None))
      checkSerialization(Status(Map("A" -> ByteString.fromString("a"),
        "B" -> ByteString.fromString("b"))))
      checkSerialization(Gossip(Map("A" -> DataEnvelope(data1),
        "B" -> DataEnvelope(GSet() :+ "b" :+ "c"))))
    }

  }
}
