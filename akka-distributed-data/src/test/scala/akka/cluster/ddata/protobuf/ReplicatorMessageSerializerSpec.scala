/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.protobuf

import java.lang.IllegalArgumentException

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
import akka.cluster.ddata.PruningState.PruningInitialized
import akka.cluster.ddata.PruningState.PruningPerformed
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata.Replicator.Internal._
import akka.testkit.TestKit
import akka.util.{ unused, ByteString }
import akka.cluster.UniqueAddress
import akka.remote.RARP
import com.typesafe.config.ConfigFactory
import akka.cluster.ddata.DurableStore.DurableDataEnvelope
import akka.cluster.ddata.GCounter
import akka.cluster.ddata.VersionVector
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORMultiMap

class ReplicatorMessageSerializerSpec
    extends TestKit(
      ActorSystem(
        "ReplicatorMessageSerializerSpec",
        ConfigFactory.parseString("""
    akka.actor.provider=cluster
    akka.remote.netty.tcp.port=0
    akka.remote.artery.canonical.port = 0
    akka.actor {
      serialize-messages = off
      allow-java-serialization = off
    }
    """)))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  val serializer = new ReplicatorMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  val Protocol = if (RARP(system).provider.remoteSettings.Artery.Enabled) "akka" else "akka.tcp"

  val address1 = UniqueAddress(Address(Protocol, system.name, "some.host.org", 4711), 1L)
  val address2 = UniqueAddress(Address(Protocol, system.name, "other.host.org", 4711), 2L)
  val address3 = UniqueAddress(Address(Protocol, system.name, "some.host.org", 4712), 3L)

  val keyA = GSetKey[String]("A")

  override def afterAll: Unit = {
    shutdown()
  }

  def checkSerialization[T <: AnyRef](obj: T): T = {
    val blob = serializer.toBinary(obj)
    val deserialized = serializer.fromBinary(blob, serializer.manifest(obj))
    deserialized should be(obj)
    deserialized.asInstanceOf[T]
  }

  "ReplicatorMessageSerializer" must {

    "serialize Replicator messages" in {
      val ref1 = system.actorOf(Props.empty, "ref1")
      val data1 = GSet.empty[String] + "a"
      val delta1 = GCounter.empty.increment(address1, 17).increment(address2, 2).delta.get
      val delta2 = delta1.increment(address2, 1).delta.get
      val delta3 = ORSet.empty[String].add(address1, "a").delta.get
      val delta4 = ORMultiMap.empty[String, String].addBinding(address1, "a", "b").delta.get

      checkSerialization(Get(keyA, ReadLocal))
      checkSerialization(Get(keyA, ReadMajority(2.seconds), Some("x")))
      checkSerialization(Get(keyA, ReadMajority((Int.MaxValue.toLong + 50).milliseconds), Some("x")))
      try {
        serializer.toBinary(Get(keyA, ReadMajority((Int.MaxValue.toLong * 3).milliseconds), Some("x")))
        fail("Our protobuf protocol does not support timeouts larger than unsigned ints")
      } catch {
        case e: IllegalArgumentException =>
          e.getMessage should include("unsigned int")
      }
      checkSerialization(GetSuccess(keyA, None)(data1))
      checkSerialization(GetSuccess(keyA, Some("x"))(data1))
      checkSerialization(NotFound(keyA, Some("x")))
      checkSerialization(GetFailure(keyA, Some("x")))
      checkSerialization(Subscribe(keyA, ref1))
      checkSerialization(Unsubscribe(keyA, ref1))
      checkSerialization(Changed(keyA)(data1))
      checkSerialization(DataEnvelope(data1))
      checkSerialization(
        DataEnvelope(
          data1,
          pruning = Map(
            address1 -> PruningPerformed(System.currentTimeMillis()),
            address3 -> PruningInitialized(address2, Set(address1.address)))))
      checkSerialization(Write("A", DataEnvelope(data1)))
      checkSerialization(WriteAck)
      checkSerialization(WriteNack)
      checkSerialization(DeltaNack)
      checkSerialization(Read("A"))
      checkSerialization(ReadResult(Some(DataEnvelope(data1))))
      checkSerialization(ReadResult(None))
      checkSerialization(
        Status(Map("A" -> ByteString.fromString("a"), "B" -> ByteString.fromString("b")), chunk = 3, totChunks = 10))
      checkSerialization(
        Gossip(Map("A" -> DataEnvelope(data1), "B" -> DataEnvelope(GSet() + "b" + "c")), sendBack = true))
      checkSerialization(
        DeltaPropagation(
          address1,
          reply = true,
          Map(
            "A" -> Delta(DataEnvelope(delta1), 1L, 1L),
            "B" -> Delta(DataEnvelope(delta2), 3L, 5L),
            "C" -> Delta(DataEnvelope(delta3), 1L, 1L),
            "DC" -> Delta(DataEnvelope(delta4), 1L, 1L))))

      checkSerialization(new DurableDataEnvelope(data1))
      val pruning = Map(
        address1 -> PruningPerformed(System.currentTimeMillis()),
        address3 -> PruningInitialized(address2, Set(address1.address)))
      val deserializedDurableDataEnvelope =
        checkSerialization(
          new DurableDataEnvelope(DataEnvelope(data1, pruning, deltaVersions = VersionVector(address1, 13L))))
      // equals of DurableDataEnvelope is only checking the data, PruningPerformed
      // should be serialized
      val expectedPruning = pruning.filter {
        case (_, _: PruningPerformed) => true
        case _                        => false
      }
      deserializedDurableDataEnvelope.dataEnvelope.pruning should ===(expectedPruning)
      deserializedDurableDataEnvelope.dataEnvelope.deltaVersions.size should ===(0)
    }

  }

  "Cache" must {
    import ReplicatorMessageSerializer._
    "be power of 2" in {
      intercept[IllegalArgumentException] {
        new SmallCache[String, String](3, 5.seconds, _ => null)
      }
    }

    "get added element" in {
      val cache = new SmallCache[Read, String](2, 5.seconds, _ => null)
      val a = Read("a")
      cache.add(a, "A")
      cache.get(a) should be("A")
      val b = Read("b")
      cache.add(b, "B")
      cache.get(a) should be("A")
      cache.get(b) should be("B")
    }

    "return null for non-existing elements" in {
      val cache = new SmallCache[Read, String](4, 5.seconds, _ => null)
      val a = Read("a")
      cache.get(a) should be(null)
      cache.add(a, "A")
      val b = Read("b")
      cache.get(b) should be(null)
    }

    "hold latest added elements" in {
      val cache = new SmallCache[Read, String](4, 5.seconds, _ => null)
      val a = Read("a")
      val b = Read("b")
      val c = Read("c")
      val d = Read("d")
      val e = Read("e")
      cache.add(a, "A")
      cache.get(a) should be("A")
      cache.add(b, "B")
      cache.get(a) should be("A")
      cache.add(c, "C")
      cache.get(a) should be("A")
      cache.add(d, "D")
      cache.get(a) should be("A")
      // now it is full and a will be pushed out
      cache.add(e, "E")
      cache.get(a) should be(null)
      cache.get(b) should be("B")
      cache.get(c) should be("C")
      cache.get(d) should be("D")
      cache.get(e) should be("E")

      cache.add(a, "A")
      cache.get(a) should be("A")
      cache.get(b) should be(null)
      cache.get(c) should be("C")
      cache.get(d) should be("D")
      cache.get(e) should be("E")
    }

    "handle Int wrap around" ignore { // ignored because it takes 20 seconds (but it works)
      val cache = new SmallCache[Read, String](2, 5.seconds, _ => null)
      val a = Read("a")
      val x = a -> "A"
      var n = 0
      while (n <= Int.MaxValue - 3) {
        cache.add(x)
        n += 1
      }

      cache.get(a) should be("A")

      val b = Read("b")
      val c = Read("c")
      cache.add(b, "B")
      cache.get(a) should be("A")
      cache.get(b) should be("B")

      cache.add(c, "C")
      cache.get(a) should be(null)
      cache.get(b) should be("B")
      cache.get(c) should be("C")

      cache.add(a, "A")
      cache.get(a) should be("A")
      cache.get(b) should be(null)
      cache.get(c) should be("C")
    }

    "suppory getOrAdd" in {
      var n = 0
      def createValue(@unused a: Read): AnyRef = {
        n += 1
        new AnyRef {
          override val toString = "v" + n
        }
      }

      val cache = new SmallCache[Read, AnyRef](4, 5.seconds, a => createValue(a))
      val a = Read("a")
      val v1 = cache.getOrAdd(a)
      v1.toString should be("v1")
      (cache.getOrAdd(a) should be).theSameInstanceAs(v1)
    }

    "evict cache after time-to-live" in {
      val cache = new SmallCache[Read, AnyRef](4, 10.millis, _ => null)
      val b = Read("b")
      val c = Read("c")
      cache.add(b, "B")
      cache.add(c, "C")

      Thread.sleep(30)
      cache.evict()
      cache.get(b) should be(null)
      cache.get(c) should be(null)
    }

    "not evict cache before time-to-live" in {
      val cache = new SmallCache[Read, AnyRef](4, 5.seconds, _ => null)
      val b = Read("b")
      val c = Read("c")
      cache.add(b, "B")
      cache.add(c, "C")
      cache.evict()
      cache.get(b) should be("B")
      cache.get(c) should be("C")
    }

  }
}
