/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.serialization

import scala.collection.immutable

import com.typesafe.config._

import akka.actor._
import akka.persistence._
import akka.persistence.JournalProtocol.Confirm
import akka.serialization._
import akka.testkit._

object SerializerSpecConfigs {
  val customSerializers =
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
    """

  val remoteCommon =
    """
      akka {
        actor {
          provider = "akka.remote.RemoteActorRefProvider"
        }
        remote {
          enabled-transports = ["akka.remote.netty.tcp"]
          netty.tcp.hostname = "127.0.0.1"
        }
        loglevel = ERROR
        log-dead-letters = 0
        log-dead-letters-during-shutdown = off
      }
    """

  val systemA = "akka.remote.netty.tcp.port = 0"
  val systemB = "akka.remote.netty.tcp.port = 0"

  def config(configs: String*): Config =
    configs.foldLeft(ConfigFactory.empty)((r, c) ⇒ r.withFallback(ConfigFactory.parseString(c)))
}

import SerializerSpecConfigs._

class SnapshotSerializerPersistenceSpec extends AkkaSpec(config(customSerializers)) {
  val serialization = SerializationExtension(system)

  "A snapshot serializer" must {
    "handle custom snapshot Serialization" in {
      val wrapped = Snapshot(MySnapshot("a"))
      val serializer = serialization.findSerializerFor(wrapped)

      val bytes = serializer.toBinary(wrapped)
      val deserialized = serializer.fromBinary(bytes, None)

      deserialized must be(Snapshot(MySnapshot(".a.")))
    }
  }
}

class MessageSerializerPersistenceSpec extends AkkaSpec(config(customSerializers)) {
  val serialization = SerializationExtension(system)

  "A message serializer" when {
    "not given a manifest" must {
      "handle custom ConfirmablePersistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("a"), 13, "p1", true, true, 3, List("c1", "c2"), confirmable = true, Confirm("p2", 14, "c2"), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, None)

        deserialized must be(persistent.withPayload(MyPayload(".a.")))
      }
      "handle custom Persistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("a"), 13, "p1", true, true, 0, List("c1", "c2"), confirmable = false, Confirm("p2", 14, "c2"), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, None)

        deserialized must be(persistent.withPayload(MyPayload(".a.")))
      }
    }
    "given a PersistentRepr manifest" must {
      "handle custom ConfirmablePersistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("b"), 13, "p1", true, true, 3, List("c1", "c2"), confirmable = true, Confirm("p2", 14, "c2"), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[PersistentRepr]))

        deserialized must be(persistent.withPayload(MyPayload(".b.")))
      }
      "handle custom Persistent message serialization" in {
        val persistent = PersistentRepr(MyPayload("b"), 13, "p1", true, true, 3, List("c1", "c2"), confirmable = true, Confirm("p2", 14, "c2"), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[PersistentRepr]))

        deserialized must be(persistent.withPayload(MyPayload(".b.")))
      }
    }
    "given a Confirm manifest" must {
      "handle Confirm message serialization" in {
        val confirmation = Confirm("x", 2, "y")
        val serializer = serialization.findSerializerFor(confirmation)

        val bytes = serializer.toBinary(confirmation)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[Confirm]))

        deserialized must be(confirmation)
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
      case p @ Confirm(pid, msnr, cid, wsnr, ep)                   ⇒ sender ! s"${pid},${msnr},${cid},${wsnr},${ep.path.name.startsWith("testActor")}"
    }
  }

  def port(system: ActorSystem) =
    system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress.port.get
}

class MessageSerializerRemotingSpec extends AkkaSpec(config(systemA).withFallback(config(customSerializers, remoteCommon))) with ImplicitSender {
  import MessageSerializerRemotingSpec._

  val remoteSystem = ActorSystem("remote", config(systemB).withFallback(config(customSerializers, remoteCommon)))
  val localActor = system.actorOf(Props(classOf[LocalActor], port(remoteSystem)))

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
    "serialize Confirm messages during remoting" in {
      localActor ! Confirm("a", 2, "b", 3, testActor)
      expectMsg("a,2,b,3,true")
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