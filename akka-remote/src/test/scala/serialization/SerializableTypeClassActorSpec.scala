package akka.actor.serialization


import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.serialization._
// import dispatch.json._
import akka.actor._
import ActorSerialization._
import Actor._

@RunWith(classOf[JUnitRunner])
class SerializableTypeClassActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  object BinaryFormatMyActor {
    implicit object MyActorFormat extends Format[MyActor] {
      def fromBinary(bytes: Array[Byte], act: MyActor) = {
        val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.Counter])).asInstanceOf[ProtobufProtocol.Counter]
        act.count = p.getCount
        act
      }
      def toBinary(ac: MyActor) =
        ProtobufProtocol.Counter.newBuilder.setCount(ac.count).build.toByteArray
    }
  }

  object BinaryFormatMyActorWithDualCounter {
    implicit object MyActorWithDualCounterFormat extends Format[MyActorWithDualCounter] {
      def fromBinary(bytes: Array[Byte], act: MyActorWithDualCounter) = {
        val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.DualCounter])).asInstanceOf[ProtobufProtocol.DualCounter]
        act.count1 = p.getCount1
        act.count2 = p.getCount2
        act
      }
      def toBinary(ac: MyActorWithDualCounter) =
        ProtobufProtocol.DualCounter.newBuilder.setCount1(ac.count1).setCount2(ac.count2).build.toByteArray
    }
  }

  object BinaryFormatMyStatelessActor {
    implicit object MyStatelessActorFormat extends StatelessActorFormat[MyStatelessActor]
  }

  object BinaryFormatMyStatelessActorWithMessagesInMailbox {
    implicit object MyStatelessActorFormat extends StatelessActorFormat[MyStatelessActorWithMessagesInMailbox]
  }

  object BinaryFormatMyActorWithSerializableMessages {
    implicit object MyActorWithSerializableMessagesFormat extends StatelessActorFormat[MyActorWithSerializableMessages]
  }

  object BinaryFormatMyJavaSerializableActor {
    implicit object MyJavaSerializableActorFormat extends SerializerBasedActorFormat[MyJavaSerializableActor] {
      val serializer = Serializer.Java
    }
  }

  describe("Serializable actor") {
    it("should be able to serialize and de-serialize a stateful actor") {
      import BinaryFormatMyActor._

      val actor1 = actorOf[MyActor].start
      (actor1 !! "hello").getOrElse("_") should equal("world 1")
      (actor1 !! "hello").getOrElse("_") should equal("world 2")

      val bytes = toBinary(actor1)
      val actor2 = fromBinary(bytes)
      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 3")
    }

    it("should be able to serialize and de-serialize a stateful actor with compound state") {
      import BinaryFormatMyActorWithDualCounter._

      val actor1 = actorOf[MyActorWithDualCounter].start
      (actor1 !! "hello").getOrElse("_") should equal("world 1 1")
      (actor1 !! "hello").getOrElse("_") should equal("world 2 2")

      val bytes = toBinary(actor1)
      val actor2 = fromBinary(bytes)
      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 3 3")
    }

    it("should be able to serialize and de-serialize a stateless actor") {
      import BinaryFormatMyStatelessActor._

      val actor1 = actorOf[MyStatelessActor].start
      (actor1 !! "hello").getOrElse("_") should equal("world")
      (actor1 !! "hello").getOrElse("_") should equal("world")

      val bytes = toBinary(actor1)
      val actor2 = fromBinary(bytes)
      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world")
    }

    it("should be able to serialize and de-serialize a stateful actor with a given serializer") {
      import BinaryFormatMyJavaSerializableActor._

      val actor1 = actorOf[MyJavaSerializableActor].start
      (actor1 !! "hello").getOrElse("_") should equal("world 1")
      (actor1 !! "hello").getOrElse("_") should equal("world 2")

      val bytes = toBinary(actor1)
      val actor2 = fromBinary(bytes)
      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 3")

      actor2.receiveTimeout should equal (Some(1000))
      actor1.stop
      actor2.stop
    }

    it("should be able to serialize and deserialize a MyStatelessActorWithMessagesInMailbox") {
      import BinaryFormatMyStatelessActorWithMessagesInMailbox._

      val actor1 = actorOf[MyStatelessActorWithMessagesInMailbox].start
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      actor1.mailboxSize should be > (0)
      val actor2 = fromBinary(toBinary(actor1))
      Thread.sleep(1000)
      actor2.mailboxSize should be > (0)
      (actor2 !! "hello-reply").getOrElse("_") should equal("world")

      val actor3 = fromBinary(toBinary(actor1, false))
      Thread.sleep(1000)
      actor3.mailboxSize should equal(0)
      (actor3 !! "hello-reply").getOrElse("_") should equal("world")
    }

    it("should be able to serialize and de-serialize an Actor hotswapped with 'become'") {
      import BinaryFormatMyActor._
      val actor1 = actorOf[MyActor].start
      (actor1 !! "hello").getOrElse("_") should equal("world 1")
      (actor1 !! "hello").getOrElse("_") should equal("world 2")
      actor1 ! "swap"
      (actor1 !! "hello").getOrElse("_") should equal("swapped")

      val bytes = toBinary(actor1)
      val actor2 = fromBinary(bytes)
      actor2.start

      (actor1 !! "hello").getOrElse("_") should equal("swapped")

      actor1 ! RevertHotSwap
      (actor2 !! "hello").getOrElse("_") should equal("world 3")
    }
/*
    it("should be able to serialize and de-serialize an hotswapped actor") {
      import BinaryFormatMyActor._

      val actor1 = actorOf[MyActor].start
      (actor1 !! "hello").getOrElse("_") should equal("world 1")
      (actor1 !! "hello").getOrElse("_") should equal("world 2")
      actor1 ! HotSwap {
        case "hello" =>
          self.reply("swapped")
      }
      (actor1 !! "hello").getOrElse("_") should equal("swapped")

      val bytes = toBinary(actor1)
      val actor2 = fromBinary(bytes)
      actor2.start

      (actor1 !! "hello").getOrElse("_") should equal("swapped")

      actor1 ! RevertHotSwap
      (actor2 !! "hello").getOrElse("_") should equal("world 3")
    }
*/
  }
  describe("Custom serializable actors") {
    it("should serialize and de-serialize") {
      import BinaryFormatMyActorWithSerializableMessages._

      val actor1 = actorOf[MyActorWithSerializableMessages].start
      (actor1 ! MyMessage("hello1", ("akka", 100)))
      (actor1 ! MyMessage("hello2", ("akka", 200)))
      (actor1 ! MyMessage("hello3", ("akka", 300)))
      (actor1 ! MyMessage("hello4", ("akka", 400)))
      (actor1 ! MyMessage("hello5", ("akka", 500)))
      actor1.mailboxSize should be > (0)
      val actor2 = fromBinary(toBinary(actor1))
      Thread.sleep(1000)
      actor2.mailboxSize should be > (0)
      (actor2 !! "hello-reply").getOrElse("_") should equal("world")

      val actor3 = fromBinary(toBinary(actor1, false))
      Thread.sleep(1000)
      actor3.mailboxSize should equal(0)
      (actor3 !! "hello-reply").getOrElse("_") should equal("world")
    }
  }
}

class MyActorWithDualCounter extends Actor {
  var count1 = 0
  var count2 = 0
  def receive = {
    case "hello" =>
      count1 = count1 + 1
      count2 = count2 + 1
      self.reply("world " + count1 + " " + count2)
  }
}

@serializable class MyActor extends Actor {
  var count = 0

  def receive = {
    case "hello" =>
      count = count + 1
      self.reply("world " + count)
    case "swap" =>
      become { case "hello" => self.reply("swapped") }
  }
}

class MyStatelessActor extends Actor {
  def receive = {
    case "hello" =>
      self.reply("world")
  }
}

class MyStatelessActorWithMessagesInMailbox extends Actor {
  def receive = {
    case "hello" =>
      println("# messages in mailbox " + self.mailboxSize)
      Thread.sleep(500)
    case "hello-reply" => self.reply("world")
  }
}

@serializable class MyJavaSerializableActor extends Actor {
  var count = 0
  self.receiveTimeout = Some(1000)

  def receive = {
    case "hello" =>
      count = count + 1
      self.reply("world " + count)
  }
}

class MyActorWithSerializableMessages extends Actor {
  def receive = {
    case MyMessage(s, t) =>
      println("# messages in mailbox " + self.mailboxSize)
      Thread.sleep(500)
    case "hello-reply" => self.reply("world")
  }
}

case class MyMessage(val id: String, val value: Tuple2[String, Int])
  extends Serializable.ScalaJSON[MyMessage] {

  def this() = this(null, null)

  import DefaultProtocol._
  import JsonSerialization._

  implicit val MyMessageFormat: sjson.json.Format[MyMessage] =
    asProduct2("id", "value")(MyMessage)(MyMessage.unapply(_).get)

  def toJSON: String = JsValue.toJson(tojson(this))
  def toBytes: Array[Byte] = tobinary(this)
  def fromBytes(bytes: Array[Byte]) = frombinary[MyMessage](bytes)
  def fromJSON(js: String) = fromjson[MyMessage](Js(js))
}
