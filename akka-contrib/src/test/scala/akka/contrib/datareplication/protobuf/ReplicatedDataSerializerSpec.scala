/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication.protobuf

import scala.concurrent.duration._
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.cluster.UniqueAddress
import akka.contrib.datareplication.Flag
import akka.contrib.datareplication.GCounter
import akka.contrib.datareplication.GSet
import akka.contrib.datareplication.LWWMap
import akka.contrib.datareplication.LWWRegister
import akka.contrib.datareplication.ORMap
import akka.contrib.datareplication.ORSet
import akka.contrib.datareplication.PNCounter
import akka.contrib.datareplication.PNCounterMap
import akka.contrib.datareplication.Replicator._
import akka.contrib.datareplication.Replicator.Internal._
import akka.testkit.AkkaSpec
import akka.contrib.datareplication.VectorClock

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ReplicatedDataSerializerSpec extends AkkaSpec {

  val serializer = new ReplicatedDataSerializer(system.asInstanceOf[ExtendedActorSystem])

  val address1 = UniqueAddress(Address("akka.tcp", "system", "some.host.org", 4711), 1)
  val address2 = UniqueAddress(Address("akka.tcp", "system", "other.host.org", 4711), 2)
  val address3 = UniqueAddress(Address("akka.tcp", "system", "some.host.org", 4712), 3)

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, obj.getClass)
    ref should be(obj)
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
      checkSerialization(GSet() :+ "a")
      checkSerialization(GSet() :+ "a" :+ "b")

      checkSerialization(GSet() :+ 1 :+ 2 :+ 3)
      checkSerialization(GSet() :+ address1 :+ address2)

      checkSerialization(GSet() :+ 1L :+ "2" :+ 3 :+ address1)

      checkSameContent(GSet() :+ "a" :+ "b", GSet() :+ "a" :+ "b")
      checkSameContent(GSet() :+ "a" :+ "b", GSet() :+ "b" :+ "a")
      checkSameContent(GSet() :+ address1 :+ address2 :+ address3, GSet() :+ address2 :+ address1 :+ address3)
      checkSameContent(GSet() :+ address1 :+ address2 :+ address3, GSet() :+ address3 :+ address2 :+ address1)
    }

    "serialize ORSet" in {
      checkSerialization(ORSet())
      checkSerialization(ORSet().add(address1, "a"))
      checkSerialization(ORSet().add(address1, "a").add(address2, "a"))
      checkSerialization(ORSet().add(address1, "a").remove(address2, "a"))
      checkSerialization(ORSet().add(address1, "a").add(address2, "b").remove(address1, "a"))
      checkSerialization(ORSet().add(address1, 1).add(address2, 2))
      checkSerialization(ORSet().add(address1, 1L).add(address2, 2L))
      checkSerialization(ORSet().add(address1, "a").add(address2, 2).add(address3, 3L).add(address3, address3))

      checkSameContent(
        ORSet().add(address1, "a").add(address2, "b"),
        ORSet().add(address2, "b").add(address1, "a"))

      checkSameContent(
        ORSet().add(address1, "a").add(address2, 17).remove(address3, 17),
        ORSet().add(address2, 17).remove(address3, 17).add(address1, "a"))
    }

    "serialize Flag" in {
      checkSerialization(Flag())
      checkSerialization(Flag().switchOn)
    }

    "serialize LWWRegister" in {
      checkSerialization(LWWRegister("value1"))
      checkSerialization(LWWRegister("value2").withValue("value3"))
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
      checkSerialization(ORMap())
      checkSerialization(ORMap().put(address1, "a", GSet() :+ "A"))
      checkSerialization(ORMap().put(address1, "a", GSet() :+ "A").put(address2, "b", GSet() :+ "B"))
    }

    "serialize LWWMap" in {
      checkSerialization(LWWMap())
      checkSerialization(LWWMap().put(address1, "a", "value1"))
      checkSerialization(LWWMap().put(address1, "a", "value1").put(address2, "b", 17))
    }

    "serialize PNCounterMap" in {
      checkSerialization(PNCounterMap())
      checkSerialization(PNCounterMap().increment(address1, "a", 3))
      checkSerialization(PNCounterMap().increment(address1, "a", 3).decrement(address2, "a", 2).
        increment(address2, "b", 5))
    }

    "serialize DeletedData" in {
      checkSerialization(DeletedData)
    }

    "serialize VectorClock" in {
      checkSerialization(VectorClock())
      checkSerialization(VectorClock().increment(address1))
      checkSerialization(VectorClock().increment(address1).increment(address2))

      checkSameContent(
        VectorClock().increment(address1).increment(address2).increment(address1),
        VectorClock().increment(address2).increment(address1).increment(address1))
      checkSameContent(
        VectorClock().increment(address1).increment(address3),
        VectorClock().increment(address3).increment(address1))
    }

  }
}

