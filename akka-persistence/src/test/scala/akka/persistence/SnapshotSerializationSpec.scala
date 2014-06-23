/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor.{ Props, ActorRef }
import akka.serialization.Serializer
import akka.testkit.{ ImplicitSender, AkkaSpec }
import java.io._

object SnapshotSerializationSpec {
  trait SerializationMarker

  // The FQN of the MySnapshot class needs to be long so that the total snapshot header length
  // is bigger than 255 bytes (this happens to be 269)
  object XXXXXXXXXXXXXXXXXXXX {
    class MySnapshot(val id: String) extends SerializationMarker {
      override def equals(obj: scala.Any) = obj match {
        case s: MySnapshot ⇒ s.id.equals(id)
        case _             ⇒ false
      }
    }
  }

  import XXXXXXXXXXXXXXXXXXXX._

  class MySerializer extends Serializer {
    def includeManifest: Boolean = true
    def identifier = 5177

    def toBinary(obj: AnyRef): Array[Byte] = {
      val bStream = new ByteArrayOutputStream()
      val pStream = new PrintStream(bStream)
      val msg: String = obj match {
        case s: MySnapshot ⇒ s.id
        case _             ⇒ "unknown"
      }
      pStream.println(msg)
      bStream.toByteArray
    }

    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
      val bStream = new ByteArrayInputStream(bytes)
      val reader = new BufferedReader(new InputStreamReader(bStream))
      new MySnapshot(reader.readLine())
    }
  }

  class TestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    def receive = {
      case s: String               ⇒ saveSnapshot(new MySnapshot(s))
      case SaveSnapshotSuccess(md) ⇒ probe ! md.sequenceNr
      case SnapshotOffer(md, s)    ⇒ probe ! ((md, s))
      case RecoveryCompleted       ⇒ // ignore
      case other                   ⇒ probe ! other
    }
  }

}

class SnapshotSerializationSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "SnapshotSerializationSpec", serialization = "off", extraConfig = Some(
  """
    |akka.actor {
    |  serializers {
    |    my-snapshot = "akka.persistence.SnapshotSerializationSpec$MySerializer"
    |  }
    |  serialization-bindings {
    |    "akka.persistence.SnapshotSerializationSpec$SerializationMarker" = my-snapshot
    |  }
    |}
  """.stripMargin))) with PersistenceSpec with ImplicitSender {

  import SnapshotSerializationSpec._
  import SnapshotSerializationSpec.XXXXXXXXXXXXXXXXXXXX._

  "A processor with custom Serializer" must {
    "be able to handle serialization header of more than 255 bytes" in {
      val sProcessor = system.actorOf(Props(classOf[TestProcessor], name, testActor))
      val persistenceId = name

      sProcessor ! "blahonga"
      expectMsg(0)
      val lProcessor = system.actorOf(Props(classOf[TestProcessor], name, testActor))
      lProcessor ! Recover()
      expectMsgPF() {
        case (SnapshotMetadata(`persistenceId`, 0, timestamp), state) ⇒
          state should be(new MySnapshot("blahonga"))
          timestamp should be > (0L)
      }
    }
  }
}
