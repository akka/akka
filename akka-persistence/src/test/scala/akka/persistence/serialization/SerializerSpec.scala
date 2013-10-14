package akka.persistence.serialization

import com.typesafe.config._

import akka.actor._
import akka.persistence._
import akka.serialization._
import akka.testkit._

object SerializerSpecConfigs {
  val common =
    """
      serialize-creators = on
      serialize-messages = on
    """

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
    configs.foldLeft(ConfigFactory.parseString(common))((r, c) ⇒ r.withFallback(ConfigFactory.parseString(c)))
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
      "handle custom persistent message serialization" in {
        val persistent = PersistentImpl(MyPayload("a"), 13, "p1", "c1", true, true, Seq("c1", "c2"), Confirm("p2", 14, "c2"), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, None)

        deserialized must be(persistent.withPayload(MyPayload(".a.")))
      }
    }
    "given a persistent message manifest" must {
      "handle custom persistent message serialization" in {
        val persistent = PersistentImpl(MyPayload("b"), 13, "p1", "c1", true, true, Seq("c1", "c2"), Confirm("p2", 14, "c2"), testActor, testActor)
        val serializer = serialization.findSerializerFor(persistent)

        val bytes = serializer.toBinary(persistent)
        val deserialized = serializer.fromBinary(bytes, Some(classOf[PersistentImpl]))

        deserialized must be(persistent.withPayload(MyPayload(".b.")))
      }
    }
    "given a confirmation message manifest" must {
      "handle confirmation message serialization" in {
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
      case Persistent(MyPayload(data), _) ⇒ sender ! data
      case Confirm(pid, snr, cid)         ⇒ sender ! s"${pid},${snr},${cid}"
    }
  }

  def port(system: ActorSystem, protocol: String) =
    addr(system, protocol).port.get

  def addr(system: ActorSystem, protocol: String) =
    system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
}

class MessageSerializerRemotingSpec extends AkkaSpec(config(systemA).withFallback(config(customSerializers, remoteCommon))) with ImplicitSender {
  import MessageSerializerRemotingSpec._

  val remoteSystem = ActorSystem("remote", config(systemB).withFallback(config(customSerializers, remoteCommon)))
  val localActor = system.actorOf(Props(classOf[LocalActor], port(remoteSystem, "tcp")))

  override protected def atStartup() {
    remoteSystem.actorOf(Props[RemoteActor], "remote")
  }

  override def afterTermination() {
    remoteSystem.shutdown()
    remoteSystem.awaitTermination()
  }

  "A message serializer" must {
    "custom-serialize persistent messages during remoting" in {
      localActor ! Persistent(MyPayload("a"))
      expectMsg(".a.")
    }
    "serialize confirmation messages during remoting" in {
      localActor ! Confirm("a", 2, "b")
      expectMsg("a,2,b")

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