/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.protobuf

import java.util.Base64

import akka.actor.ActorIdentity
import akka.actor.ActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Identify
import akka.cluster.ddata._
import akka.cluster.ddata.Replicator.Internal._
import akka.testkit.TestKit
import akka.cluster.UniqueAddress
import akka.remote.RARP
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.testkit.TestActors

class ReplicatedDataSerializerSpec extends TestKit(ActorSystem(
  "ReplicatedDataSerializerSpec",
  ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.provider=cluster
    akka.remote.netty.tcp.port=0
    akka.remote.artery.canonical.port = 0
    akka.actor {
      serialize-messages = off
      allow-java-serialization = off
    }
    """))) with WordSpecLike with Matchers with BeforeAndAfterAll {

  val serializer = new ReplicatedDataSerializer(system.asInstanceOf[ExtendedActorSystem])

  val Protocol = if (RARP(system).provider.remoteSettings.Artery.Enabled) "akka" else "akka.tcp"

  val address1 = UniqueAddress(Address(Protocol, system.name, "some.host.org", 4711), 1L)
  val address2 = UniqueAddress(Address(Protocol, system.name, "other.host.org", 4711), 2L)
  val address3 = UniqueAddress(Address(Protocol, system.name, "some.host.org", 4712), 3L)

  val ref1 = system.actorOf(Props.empty, "ref1")
  val ref2 = system.actorOf(Props.empty, "ref2")
  val ref3 = system.actorOf(Props.empty, "ref3")

  override def afterAll: Unit = {
    shutdown()
  }

  /**
   * Given a blob created with the previous serializer (with only string keys for maps). If we deserialize it and then
   * serialize it again and arive at the same BLOB we can assume that we are compatible in both directions.
   */
  def checkCompatibility(oldBlobAsBase64: String, obj: AnyRef): Unit = {
    val oldBlob = Base64.getDecoder.decode(oldBlobAsBase64)
    val deserialized = serializer.fromBinary(oldBlob, serializer.manifest(obj))
    val newBlob = serializer.toBinary(deserialized)
    newBlob should equal(oldBlob)
  }

  def checkSerialization(obj: AnyRef): Int = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, serializer.manifest(obj))
    ref should be(obj)
    blob.length
  }

  def checkSameContent(a: AnyRef, b: AnyRef): Unit = {
    a should be(b)
    val blobA = serializer.toBinary(a)
    val blobB = serializer.toBinary(b)
    blobA.toSeq should be(blobB.toSeq)
  }

  "ReplicatedDataSerializer" must {

    "serialize GSet" in {
      checkSerialization(GSet())
      checkSerialization(GSet() + "a")
      checkSerialization(GSet() + "a" + "b")

      checkSerialization(GSet() + 1 + 2 + 3)
      checkSerialization(GSet() + ref1 + ref2)

      checkSerialization(GSet() + 1L + "2" + 3 + ref1)

      checkSameContent(GSet() + "a" + "b", GSet() + "a" + "b")
      checkSameContent(GSet() + "a" + "b", GSet() + "b" + "a")
      checkSameContent(GSet() + ref1 + ref2 + ref3, GSet() + ref2 + ref1 + ref3)
      checkSameContent(GSet() + ref1 + ref2 + ref3, GSet() + ref3 + ref2 + ref1)
    }

    "serialize ORSet" in {
      checkSerialization(ORSet())
      checkSerialization(ORSet().add(address1, "a"))
      checkSerialization(ORSet().add(address1, "a").add(address2, "a"))
      checkSerialization(ORSet().add(address1, "a").remove(address2, "a"))
      checkSerialization(ORSet().add(address1, "a").add(address2, "b").remove(address1, "a"))
      checkSerialization(ORSet().add(address1, 1).add(address2, 2))
      checkSerialization(ORSet().add(address1, 1L).add(address2, 2L))
      checkSerialization(ORSet().add(address1, "a").add(address2, 2).add(address3, 3L).add(address3, ref3))

      val s1 = ORSet().add(address1, "a").add(address2, "b")
      val s2 = ORSet().add(address2, "b").add(address1, "a")

      checkSameContent(s1.merge(s2), s2.merge(s1))

      val s3 = ORSet().add(address1, "a").add(address2, 17).remove(address3, 17)
      val s4 = ORSet().add(address2, 17).remove(address3, 17).add(address1, "a")
      checkSameContent(s3.merge(s4), s4.merge(s3))

      // ORSet with ActorRef
      checkSerialization(ORSet().add(address1, ref1))
      checkSerialization(ORSet().add(address1, ref1).add(address1, ref2))
      checkSerialization(ORSet().add(address1, ref1).add(address1, "a").add(address2, ref2) add (address2, "b"))

      val s5 = ORSet().add(address1, "a").add(address2, ref1)
      val s6 = ORSet().add(address2, ref1).add(address1, "a")
      checkSameContent(s5.merge(s6), s6.merge(s5))
    }

    "serialize ORSet with ActorRef message sent between two systems" in {
      val system2 = ActorSystem(system.name, system.settings.config)
      try {
        val echo1 = system.actorOf(TestActors.echoActorProps, "echo1")
        system2.actorOf(TestActors.echoActorProps, "echo2")

        system.actorSelection(RootActorPath(Cluster(system2).selfAddress) / "user" / "echo2").tell(
          Identify("2"), testActor)
        val echo2 = expectMsgType[ActorIdentity].ref.get

        val msg = ORSet.empty[ActorRef].add(Cluster(system), echo1).add(Cluster(system), echo2)
        echo2.tell(msg, testActor)
        val reply = expectMsgType[ORSet[ActorRef]]
        reply.elements should ===(Set(echo1, echo2))

      } finally {
        shutdown(system2)
      }
    }

    "serialize ORSet delta" in {
      checkSerialization(ORSet().add(address1, "a").delta.get)
      checkSerialization(ORSet().add(address1, "a").resetDelta.remove(address2, "a").delta.get)
      checkSerialization(ORSet().add(address1, "a").remove(address2, "a").delta.get)
      checkSerialization(ORSet().add(address1, "a").resetDelta.clear(address2).delta.get)
      checkSerialization(ORSet().add(address1, "a").clear(address2).delta.get)
    }

    "serialize large GSet" in {
      val largeSet = (10000 until 20000).foldLeft(GSet.empty[String]) {
        case (acc, n) ⇒ acc.resetDelta.add(n.toString)
      }
      val numberOfBytes = checkSerialization(largeSet)
      info(s"size of GSet with ${largeSet.size} elements: $numberOfBytes bytes")
      numberOfBytes should be <= (80000)
    }

    "serialize large ORSet" in {
      val largeSet = (10000 until 20000).foldLeft(ORSet.empty[String]) {
        case (acc, n) ⇒
          val address = (n % 3) match {
            case 0 ⇒ address1
            case 1 ⇒ address2
            case 2 ⇒ address3
          }
          acc.resetDelta.add(address, n.toString)
      }
      val numberOfBytes = checkSerialization(largeSet)
      // note that ORSet is compressed, and therefore smaller than GSet
      info(s"size of ORSet with ${largeSet.size} elements: $numberOfBytes bytes")
      numberOfBytes should be <= (50000)
    }

    "serialize Flag" in {
      checkSerialization(Flag())
      checkSerialization(Flag().switchOn)
    }

    "serialize LWWRegister" in {
      checkSerialization(LWWRegister(address1, "value1", LWWRegister.defaultClock[String]))
      checkSerialization(LWWRegister(address1, "value2", LWWRegister.defaultClock[String])
        .withValue(address2, "value3", LWWRegister.defaultClock[String]))
    }

    "serialize GCounter" in {
      checkSerialization(GCounter())
      checkSerialization(GCounter().increment(address1, 3))
      checkSerialization(GCounter().increment(address1, 2).increment(address2, 5))

      checkSameContent(
        GCounter().increment(address1, 2).increment(address2, 5),
        GCounter().increment(address2, 5).increment(address1, 1).increment(address1, 1))
      checkSameContent(
        GCounter().increment(address1, 2).increment(address3, 5),
        GCounter().increment(address3, 5).increment(address1, 2))
    }

    "serialize PNCounter" in {
      checkSerialization(PNCounter())
      checkSerialization(PNCounter().increment(address1, 3))
      checkSerialization(PNCounter().increment(address1, 3).decrement(address1, 1))
      checkSerialization(PNCounter().increment(address1, 2).increment(address2, 5))
      checkSerialization(PNCounter().increment(address1, 2).increment(address2, 5).decrement(address1, 1))

      checkSameContent(
        PNCounter().increment(address1, 2).increment(address2, 5),
        PNCounter().increment(address2, 5).increment(address1, 1).increment(address1, 1))
      checkSameContent(
        PNCounter().increment(address1, 2).increment(address3, 5),
        PNCounter().increment(address3, 5).increment(address1, 2))
      checkSameContent(
        PNCounter().increment(address1, 2).decrement(address1, 1).increment(address3, 5),
        PNCounter().increment(address3, 5).increment(address1, 2).decrement(address1, 1))
    }

    "serialize ORMap" in {
      checkSerialization(ORMap().put(address1, "a", GSet() + "A").put(address2, "b", GSet() + "B"))
      checkSerialization(ORMap().put(address1, 1, GSet() + "A"))
      checkSerialization(ORMap().put(address1, 1L, GSet() + "A"))
      // use Flag for this test as object key because it is serializable
      checkSerialization(ORMap().put(address1, Flag(), GSet() + "A"))
    }

    "serialize ORMap delta" in {
      checkSerialization(ORMap().put(address1, "a", GSet() + "A").put(address2, "b", GSet() + "B").delta.get)
      checkSerialization(ORMap().put(address1, "a", GSet() + "A").resetDelta.remove(address2, "a").delta.get)
      checkSerialization(ORMap().put(address1, "a", GSet() + "A").remove(address2, "a").delta.get)
      checkSerialization(ORMap().put(address1, 1, GSet() + "A").delta.get)
      checkSerialization(ORMap().put(address1, 1L, GSet() + "A").delta.get)
      checkSerialization(ORMap.empty[String, ORSet[String]]
        .put(address1, "a", ORSet.empty[String].add(address1, "A"))
        .put(address2, "b", ORSet.empty[String].add(address2, "B"))
        .updated(address1, "a", ORSet.empty[String])(_.add(address1, "C")).delta.get)
      checkSerialization(ORMap.empty[String, ORSet[String]]
        .resetDelta
        .updated(address1, "a", ORSet.empty[String])(_.add(address1, "C")).delta.get)
      // use Flag for this test as object key because it is serializable
      checkSerialization(ORMap().put(address1, Flag(), GSet() + "A").delta.get)
    }

    "be compatible with old ORMap serialization" in {
      // Below blob was created with previous version of the serializer
      val oldBlobAsBase64 = "H4sIAAAAAAAAAOOax8jlyaXMJc8lzMWXX5KRWqSXkV9copdflC7wXEWUiYGBQRaIGQQkuJS45LiEuHiL83NTUdQwwtWIC6kQpUqVKAulGBOlGJOE+LkYE4W4uJi5GB0FuJUYnUACSRABJ7AAAOLO3C3DAAAA"
      checkCompatibility(oldBlobAsBase64, ORMap())
    }

    "serialize LWWMap" in {
      checkSerialization(LWWMap())
      checkSerialization(LWWMap().put(address1, "a", "value1", LWWRegister.defaultClock[Any]))
      checkSerialization(LWWMap().put(address1, 1, "value1", LWWRegister.defaultClock[Any]))
      checkSerialization(LWWMap().put(address1, 1L, "value1", LWWRegister.defaultClock[Any]))
      checkSerialization(LWWMap().put(address1, Flag(), "value1", LWWRegister.defaultClock[Any]))
      checkSerialization(LWWMap().put(address1, "a", "value1", LWWRegister.defaultClock[Any])
        .put(address2, "b", 17, LWWRegister.defaultClock[Any]))
    }

    "be compatible with old LWWMap serialization" in {
      // Below blob was created with previous version of the serializer
      val oldBlobAsBase64 = "H4sIAAAAAAAAAOPy51LhUuKS4xLi4i3Oz03Vy8gvLtHLL0oXeK4iysjAwCALxAwC0kJEqZJiTBSy4AISxhwzrl2fuyRMiIAWKS4utrLEnNJUQwERAD96/peLAAAA"
      checkCompatibility(oldBlobAsBase64, LWWMap())
    }

    "serialize PNCounterMap" in {
      checkSerialization(PNCounterMap())
      checkSerialization(PNCounterMap().increment(address1, "a", 3))
      checkSerialization(PNCounterMap().increment(address1, 1, 3))
      checkSerialization(PNCounterMap().increment(address1, 1L, 3))
      checkSerialization(PNCounterMap().increment(address1, Flag(), 3))
      checkSerialization(PNCounterMap().increment(address1, "a", 3).decrement(address2, "a", 2).
        increment(address2, "b", 5))
    }

    "be compatible with old PNCounterMap serialization" in {
      // Below blob was created with previous version of the serializer
      val oldBlobAsBase64 = "H4sIAAAAAAAAAOPy51LhUuKS4xLi4i3Oz03Vy8gvLtHLL0oXeK4iysjAwCALxAwC8kJEqZJiTBTS4wISmlyqXMqE1AsxMgsxAADYQs/9gQAAAA=="
      checkCompatibility(oldBlobAsBase64, PNCounterMap())
    }

    "serialize ORMultiMap" in {
      checkSerialization(ORMultiMap())
      checkSerialization(ORMultiMap().addBinding(address1, "a", "A"))
      checkSerialization(ORMultiMap().addBinding(address1, 1, "A"))
      checkSerialization(ORMultiMap().addBinding(address1, 1L, "A"))
      checkSerialization(ORMultiMap().addBinding(address1, Flag(), "A"))
      checkSerialization(ORMultiMap.empty[String, String]
        .addBinding(address1, "a", "A1")
        .put(address2, "b", Set("B1", "B2", "B3"))
        .addBinding(address2, "a", "A2"))

      val m1 = ORMultiMap.empty[String, String].addBinding(address1, "a", "A1").addBinding(address2, "a", "A2")
      val m2 = ORMultiMap.empty[String, String].put(address2, "b", Set("B1", "B2", "B3"))
      checkSameContent(m1.merge(m2), m2.merge(m1))
      checkSerialization(ORMultiMap.empty[String, String].addBinding(address1, "a", "A1").addBinding(address1, "a", "A2").delta.get)
      val m3 = ORMultiMap.empty[String, String].addBinding(address1, "a", "A1")
      val d3 = m3.resetDelta.addBinding(address1, "a", "A2").addBinding(address1, "a", "A3").delta.get
      checkSerialization(d3)
    }

    "be compatible with old ORMultiMap serialization" in {
      // Below blob was created with previous version of the serializer
      val oldBlobAsBase64 = "H4sIAAAAAAAAAOPy51LhUuKS4xLi4i3Oz03Vy8gvLtHLL0oXeK4iysjAwCALxAwCakJEqZJiTBQK4QISxJmqSpSpqlKMjgDlsHjDpwAAAA=="
      checkCompatibility(oldBlobAsBase64, ORMultiMap())
    }

    "serialize ORMultiMap withValueDeltas" in {
      checkSerialization(ORMultiMap._emptyWithValueDeltas)
      checkSerialization(ORMultiMap._emptyWithValueDeltas.addBinding(address1, "a", "A"))
      checkSerialization(ORMultiMap._emptyWithValueDeltas.addBinding(address1, 1, "A"))
      checkSerialization(ORMultiMap._emptyWithValueDeltas.addBinding(address1, 1L, "A"))
      checkSerialization(ORMultiMap._emptyWithValueDeltas.addBinding(address1, Flag(), "A"))
      checkSerialization(ORMultiMap.emptyWithValueDeltas[String, String].addBinding(address1, "a", "A").remove(address1, "a").delta.get)
      checkSerialization(ORMultiMap.emptyWithValueDeltas[String, String]
        .addBinding(address1, "a", "A1")
        .put(address2, "b", Set("B1", "B2", "B3"))
        .addBinding(address2, "a", "A2"))

      val m1 = ORMultiMap.emptyWithValueDeltas[String, String].addBinding(address1, "a", "A1").addBinding(address2, "a", "A2")
      val m2 = ORMultiMap.emptyWithValueDeltas[String, String].put(address2, "b", Set("B1", "B2", "B3"))
      checkSameContent(m1.merge(m2), m2.merge(m1))
    }

    "serialize DeletedData" in {
      checkSerialization(DeletedData)
    }

    "serialize VersionVector" in {
      checkSerialization(VersionVector())
      checkSerialization(VersionVector().increment(address1))
      checkSerialization(VersionVector().increment(address1).increment(address2))

      val v1 = VersionVector().increment(address1).increment(address1)
      val v2 = VersionVector().increment(address2)
      checkSameContent(v1.merge(v2), v2.merge(v1))
    }

  }
}

