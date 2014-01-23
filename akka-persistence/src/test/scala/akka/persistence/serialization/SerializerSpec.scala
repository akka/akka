/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.serialization

import scala.collection.immutable

import com.typesafe.config._

import akka.actor._
import akka.persistence._
import akka.serialization._
import akka.testkit._

object SerializerSpecConfigs {
  val customSerializers = ConfigFactory.parseString(
    """
      akka.actor {
        serializers {
          my-payload = "akka.persistence.serialization.MyPayloadSerializer"
          my-snapshot = "akka.persistence.serialization.MySnapshotSerializer"
        }
        serialization-bindings {
          "akka.persistence.serialization.MyPayload" = my-payload
          "akka.persistence.serialization.MySnapshot" = my-snapshot
        }
      }
    """)

  val remote = ConfigFactory.parseString(
    """
      akka {
        actor {
          provider = "akka.remote.RemoteActorRefProvider"
        }
        remote {
          enabled-transports = ["akka.remote.netty.tcp"]
          netty.tcp {
            hostname = "127.0.0.1"
            port = 0
          }
        }
        loglevel = ERROR
        log-dead-letters = 0
        log-dead-letters-during-shutdown = off
      }
    """)

  def config(configs: String*): Config =
    configs.foldLeft(ConfigFactory.empty)((r, c) ⇒ r.withFallback(ConfigFactory.parseString(c)))
}

import SerializerSpecConfigs._

class SnapshotSerializerPersistenceSpec extends AkkaSpec(customSerializers) {
  val serialization = SerializationExtension(system)

  "A snapshot serializer" must {
    "handle custom snapshot Serialization" in {
      val wrapped = Snapshot(MySnapshot("a"))
      val serializer = serialization.findSerializerFor(wrapped)

      val bytes = serializer.toBinary(wrapped)
      val deserialized = serializer.fromBinary(bytes, None)

      deserialized should be(Snapshot(MySnapshot(".a.")))
    }
  }
}

class MessageSerializerPersistenceSpec extends AkkaSpec(customSerializers) {
  val serialization = SerializationExtension(system)

  "A message serializer" when {
    "not given a manifest" must {
      "handle custom ConfirmablePersistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("a"), 13, "p1", true, 3, List("c1", "c2"), confirmable = true, DeliveredByChannel("p2", "c2", 14), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, None)

        deserialized should be(persistent.withPayload(MyPayload(".a.")))
      }
      "handle custom Persistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("a"), 13, "p1", true, 0, List("c1", "c2"), confirmable = false, DeliveredByChannel("p2", "c2", 14), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, None)

        deserialized should be(persistent.withPayload(MyPayload(".a.")))
      }
    }
    "given a PersistentRepr manifest" must {
      "handle custom ConfirmablePersistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("b"), 13, "p1", true, 3, List("c1", "c2"), confirmable = true, DeliveredByChannel("p2", "c2", 14), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[PersistentRepr]))

        deserialized should be(persistent.withPayload(MyPayload(".b.")))
      }
      "handle custom Persistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("b"), 13, "p1", true, 3, List("c1", "c2"), confirmable = true, DeliveredByChannel("p2", "c2", 14), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[PersistentRepr]))

        deserialized should be(persistent.withPayload(MyPayload(".b.")))
      }
    }
    "given a Confirm manifest" must {
      "handle DeliveryByChannel message serialization" in {
        val confirmation = DeliveredByChannel("p2", "c2", 14)
        val serializer = serialization.findSerializerFor(confirmation)

        val bytes = serializer.toBinary(confirmation)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[DeliveredByChannel]))

        deserialized should be(confirmation)
      }
      "handle DeliveredByPersistentChannel message serialization" in {
        val confirmation = DeliveredByPersistentChannel("c2", 14)
        val serializer = serialization.findSerializerFor(confirmation)

        val bytes = serializer.toBinary(confirmation)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[DeliveredByPersistentChannel]))

        deserialized should be(confirmation)
      }
    }
  }
}

object MessageSerializerRemotingSpec {
  class LocalActor(port: Int) extends Actor {
    def receive = {
      case m ⇒ context.actorSelection(s"akka.tcp://remote@127.0.0.1:${port}/user/remote") tell (m, sender)
    }
  }

  class RemoteActor extends Actor {
    def receive = {
      case PersistentBatch(Persistent(MyPayload(data), _) +: tail) ⇒ sender ! s"b${data}"
      case ConfirmablePersistent(MyPayload(data), _, _)            ⇒ sender ! s"c${data}"
      case Persistent(MyPayload(data), _)                          ⇒ sender ! s"p${data}"
      case DeliveredByChannel(pid, cid, msnr, dsnr, ep)            ⇒ sender ! s"${pid},${cid},${msnr},${dsnr},${ep.path.name.startsWith("testActor")}"
      case DeliveredByPersistentChannel(cid, msnr, dsnr, ep)       ⇒ sender ! s"${cid},${msnr},${dsnr},${ep.path.name.startsWith("testActor")}"
      case Deliver(Persistent(payload, _), dp)                     ⇒ context.actorSelection(dp) ! payload
    }
  }

  def port(system: ActorSystem) =
    address(system).port.get

  def address(system: ActorSystem) =
    system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
}

class MessageSerializerRemotingSpec extends AkkaSpec(remote.withFallback(customSerializers)) with ImplicitSender with DefaultTimeout {
  import MessageSerializerRemotingSpec._

  val remoteSystem = ActorSystem("remote", remote.withFallback(customSerializers))
  val localActor = system.actorOf(Props(classOf[LocalActor], port(remoteSystem)), "local")

  override protected def atStartup() {
    remoteSystem.actorOf(Props[RemoteActor], "remote")
  }

  override def afterTermination() {
    remoteSystem.shutdown()
    remoteSystem.awaitTermination()
  }

  "A message serializer" must {
    "custom-serialize Persistent messages during remoting" in {
      localActor ! Persistent(MyPayload("a"))
      expectMsg("p.a.")
    }
    "custom-serialize ConfirmablePersistent messages during remoting" in {
      localActor ! PersistentRepr(MyPayload("a"), confirmable = true)
      expectMsg("c.a.")
    }
    "custom-serialize Persistent message batches during remoting" in {
      localActor ! PersistentBatch(immutable.Seq(Persistent(MyPayload("a"))))
      expectMsg("b.a.")
    }
    "serialize DeliveredByChannel messages during remoting" in {
      localActor ! DeliveredByChannel("a", "b", 2, 3, testActor)
      expectMsg("a,b,2,3,true")
    }
    "serialize DeliveredByPersistentChannel messages during remoting" in {
      localActor ! DeliveredByPersistentChannel("c", 2, 3, testActor)
      expectMsg("c,2,3,true")
    }
    "serialize Deliver messages during remoting" in {
      localActor ! Deliver(Persistent("a"), ActorPath.fromString(testActor.path.toStringWithAddress(address(system))))
      expectMsg("a")
    }
  }
}

case class MyPayload(data: String)
case class MySnapshot(data: String)

class MyPayloadSerializer extends Serializer {
  val MyPayloadClass = classOf[MyPayload]

  def identifier: Int = 77123
  def includeManifest: Boolean = true

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case MyPayload(data) ⇒ s".${data}".getBytes("UTF-8")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = manifest match {
    case Some(MyPayloadClass) ⇒ MyPayload(s"${new String(bytes, "UTF-8")}.")
    case Some(c)              ⇒ throw new Exception(s"unexpected manifest ${c}")
    case None                 ⇒ throw new Exception("no manifest")
  }
}

class MySnapshotSerializer extends Serializer {
  val MySnapshotClass = classOf[MySnapshot]

  def identifier: Int = 77124
  def includeManifest: Boolean = true

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case MySnapshot(data) ⇒ s".${data}".getBytes("UTF-8")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = manifest match {
    case Some(MySnapshotClass) ⇒ MySnapshot(s"${new String(bytes, "UTF-8")}.")
    case Some(c)               ⇒ throw new Exception(s"unexpected manifest ${c}")
    case None                  ⇒ throw new Exception("no manifest")
  }
}