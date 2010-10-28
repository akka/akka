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
class UntypedActorSerializationSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  class MyUntypedActorFormat extends Format[MyUntypedActor] {
    def fromBinary(bytes: Array[Byte], act: MyUntypedActor) = {
      val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.Counter])).asInstanceOf[ProtobufProtocol.Counter]
      act.count = p.getCount
      act
    }
    def toBinary(ac: MyUntypedActor) =
      ProtobufProtocol.Counter.newBuilder.setCount(ac.count).build.toByteArray
  }

  class MyUntypedActorWithDualCounterFormat extends Format[MyUntypedActorWithDualCounter] {
    def fromBinary(bytes: Array[Byte], act: MyUntypedActorWithDualCounter) = {
      val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.DualCounter])).asInstanceOf[ProtobufProtocol.DualCounter]
      act.count1 = p.getCount1
      act.count2 = p.getCount2
      act
    }
    def toBinary(ac: MyUntypedActorWithDualCounter) =
      ProtobufProtocol.DualCounter.newBuilder.setCount1(ac.count1).setCount2(ac.count2).build.toByteArray
  }

  object MyUntypedStatelessActorFormat extends StatelessActorFormat[MyUntypedStatelessActor]

  describe("Serializable untyped actor") {
    it("should be able to serialize and de-serialize a stateful untyped actor") {
      val actor1 = UntypedActor.actorOf[MyUntypedActor](classOf[MyUntypedActor]).start
      actor1.sendRequestReply("hello") should equal("world 1")
      actor1.sendRequestReply("debasish") should equal("hello debasish 2")

      val f = new MyUntypedActorFormat
      val bytes = toBinaryJ(actor1, f)
      val actor2 = fromBinaryJ(bytes, f)
      actor2.start
      actor2.sendRequestReply("hello") should equal("world 3")
    }

    it("should be able to serialize and de-serialize a stateful actor with compound state") {
      val actor1 = actorOf[MyUntypedActorWithDualCounter].start
      actor1.sendRequestReply("hello") should equal("world 1 1")
      actor1.sendRequestReply("hello") should equal("world 2 2")

      val f = new MyUntypedActorWithDualCounterFormat
      val bytes = toBinaryJ(actor1, f)
      val actor2 = fromBinaryJ(bytes, f)
      actor2.start
      actor2.sendRequestReply("hello") should equal("world 3 3")
    }

    it("should be able to serialize and de-serialize a stateless actor") {
      val actor1 = actorOf[MyUntypedStatelessActor].start
      actor1.sendRequestReply("hello") should equal("world")
      actor1.sendRequestReply("hello") should equal("world")

      val bytes = toBinaryJ(actor1, MyUntypedStatelessActorFormat)
      val actor2 = fromBinaryJ(bytes, MyUntypedStatelessActorFormat)
      actor2.start
      actor2.sendRequestReply("hello") should equal("world")
    }
  }
}

class MyUntypedActor extends UntypedActor {
  var count = 0
  def onReceive(message: Any): Unit = message match {
    case m: String if m == "hello" =>
      count = count + 1
      getContext.replyUnsafe("world " + count)
    case m: String =>
      count = count + 1
      getContext.replyUnsafe("hello " + m + " " + count)
    case _ =>
      throw new Exception("invalid message type")
  }
}

class MyUntypedActorWithDualCounter extends UntypedActor {
  var count1 = 0
  var count2 = 0

  def onReceive(message: Any): Unit = message match {
    case m: String if m == "hello" =>
      count1 = count1 + 1
      count2 = count2 + 1
      getContext.replyUnsafe("world " + count1 + " " + count2)
    case m: String =>
      count1 = count1 + 1
      count2 = count2 + 1
      getContext.replyUnsafe("hello " + m + " " + count1 + " " + count2)
    case _ =>
      throw new Exception("invalid message type")
  }
}

class MyUntypedStatelessActor extends UntypedActor {
  def onReceive(message: Any): Unit = message match {
    case m: String if m == "hello" =>
      getContext.replyUnsafe("world")
    case m: String =>
      getContext.replyUnsafe("hello " + m)
    case _ =>
      throw new Exception("invalid message type")
  }
}
