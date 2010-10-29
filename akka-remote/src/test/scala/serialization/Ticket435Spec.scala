package akka.actor.serialization


import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.serialization._
import akka.actor._
import ActorSerialization._
import Actor._

@RunWith(classOf[JUnitRunner])
class Ticket435Spec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  object BinaryFormatMyStatefulActor {
    implicit object MyStatefulActorFormat extends Format[MyStatefulActor] {
      def fromBinary(bytes: Array[Byte], act: MyStatefulActor) = {
        val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.Counter])).asInstanceOf[ProtobufProtocol.Counter]
        act.count = p.getCount
        act
      }
      def toBinary(ac: MyStatefulActor) =
        ProtobufProtocol.Counter.newBuilder.setCount(ac.count).build.toByteArray
    }
  }

  object BinaryFormatMyStatelessActorWithMessagesInMailbox {
    implicit object MyStatelessActorFormat extends StatelessActorFormat[MyStatelessActorWithMessagesInMailbox]
  }

  describe("Serializable actor") {

    it("should be able to serialize and deserialize a stateless actor with messages in mailbox") {
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

    it("should serialize the mailbox optionally") {
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

      val actor2 = fromBinary(toBinary(actor1, false))
      Thread.sleep(1000)
      actor2.mailboxSize should equal(0)
      (actor2 !! "hello-reply").getOrElse("_") should equal("world")
    }

    it("should be able to serialize and deserialize a stateful actor with messages in mailbox") {
      import BinaryFormatMyStatefulActor._

      val actor1 = actorOf[MyStatefulActor].start
      (actor1 ! "hi")
      (actor1 ! "hi")
      (actor1 ! "hi")
      (actor1 ! "hi")
      (actor1 ! "hi")
      (actor1 ! "hi")
      (actor1 ! "hi")
      (actor1 ! "hi")
      (actor1 ! "hi")
      (actor1 ! "hi")
      actor1.mailboxSize should be > (0)
      val actor2 = fromBinary(toBinary(actor1))
      Thread.sleep(1000)
      actor2.mailboxSize should be > (0)
      (actor2 !! "hello").getOrElse("_") should equal("world 1")

      val actor3 = fromBinary(toBinary(actor1, false))
      Thread.sleep(1000)
      actor3.mailboxSize should equal(0)
      (actor3 !! "hello").getOrElse("_") should equal("world 1")
    }
  }
}

class MyStatefulActor extends Actor {
  var count = 0

  def receive = {
    case "hi" =>
      println("# messages in mailbox " + self.mailboxSize)
      Thread.sleep(500)
    case "hello" =>
      count = count + 1
      self.reply("world " + count)
  }
}
