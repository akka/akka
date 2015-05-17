/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.ddata.protobuf

import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Props
import akka.cluster.ddata.GSet
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.PruningState
import akka.cluster.ddata.PruningState.PruningInitialized
import akka.cluster.ddata.PruningState.PruningPerformed
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata.Replicator.Internal._
import akka.testkit.TestKit
import akka.util.ByteString
import akka.cluster.UniqueAddress
import com.typesafe.config.ConfigFactory

class ReplicatorMessageSerializerSpec extends TestKit(ActorSystem("ReplicatorMessageSerializerSpec",
  ConfigFactory.parseString("""
    akka.actor.provider=akka.cluster.ClusterActorRefProvider
    akka.remote.netty.tcp.port=0
    """))) with WordSpecLike with Matchers with BeforeAndAfterAll {

  val serializer = new ReplicatorMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  val address1 = UniqueAddress(Address("akka.tcp", system.name, "some.host.org", 4711), 1)
  val address2 = UniqueAddress(Address("akka.tcp", system.name, "other.host.org", 4711), 2)
  val address3 = UniqueAddress(Address("akka.tcp", system.name, "some.host.org", 4712), 3)

  val keyA = GSetKey[String]("A")

  override def afterAll {
    shutdown()
  }

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, serializer.manifest(obj))
    ref should be(obj)
  }

  "ReplicatorMessageSerializer" must {

    "serialize Replicator messages" in {
      val ref1 = system.actorOf(Props.empty, "ref1")
      val data1 = GSet.empty[String] + "a"

      checkSerialization(Get(keyA, ReadLocal))
      checkSerialization(Get(keyA, ReadMajority(2.seconds), Some("x")))
      checkSerialization(GetSuccess(keyA, None)(data1))
      checkSerialization(GetSuccess(keyA, Some("x"))(data1))
      checkSerialization(NotFound(keyA, Some("x")))
      checkSerialization(GetFailure(keyA, Some("x")))
      checkSerialization(Subscribe(keyA, ref1))
      checkSerialization(Unsubscribe(keyA, ref1))
      checkSerialization(Changed(keyA)(data1))
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
        "B" -> ByteString.fromString("b")), chunk = 3, totChunks = 10))
      checkSerialization(Gossip(Map("A" -> DataEnvelope(data1),
        "B" -> DataEnvelope(GSet() + "b" + "c")), sendBack = true))
    }

  }
}
