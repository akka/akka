/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.serialization

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.serialization._
import akka.actor._

import TypedActorSerialization._
import Actor._
import akka.remote.{RemoteClient, RemoteServer}
import akka.actor.remote.ServerInitiatedRemoteActorSpec.RemoteActorSpecActorUnidirectional

@RunWith(classOf[JUnitRunner])
class TypedActorSerializationSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  var server1: RemoteServer = null
  var typedActor: MyTypedActor = null

  override def beforeAll = {
    server1 = new RemoteServer().start("localhost", 9991)
    typedActor = TypedActor.newInstance(classOf[MyTypedActor], classOf[MyTypedActorImpl], 1000)
    server1.registerTypedActor("typed-actor-service", typedActor)
    Thread.sleep(1000)
  }

  // make sure the servers shutdown cleanly after the test has finished
  override def afterAll = {
    try {
      TypedActor.stop(typedActor)
      server1.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  }

  object MyTypedStatelessActorFormat extends StatelessActorFormat[MyStatelessTypedActorImpl]

  class MyTypedActorFormat extends Format[MyTypedActorImpl] {
    def fromBinary(bytes: Array[Byte], act: MyTypedActorImpl) = {
      val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.Counter])).asInstanceOf[ProtobufProtocol.Counter]
      act.count = p.getCount
      act
    }
    def toBinary(ac: MyTypedActorImpl) =
      ProtobufProtocol.Counter.newBuilder.setCount(ac.count).build.toByteArray
  }

  class MyTypedActorWithDualCounterFormat extends Format[MyTypedActorWithDualCounter] {
    def fromBinary(bytes: Array[Byte], act: MyTypedActorWithDualCounter) = {
      val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.DualCounter])).asInstanceOf[ProtobufProtocol.DualCounter]
      act.count1 = p.getCount1
      act.count2 = p.getCount2
      act
    }
    def toBinary(ac: MyTypedActorWithDualCounter) =
      ProtobufProtocol.DualCounter.newBuilder.setCount1(ac.count1).setCount2(ac.count2).build.toByteArray
  }


  describe("Serializable typed actor") {

    it("should be able to serialize and de-serialize a stateless typed actor") {
      val typedActor1 = TypedActor.newInstance(classOf[MyTypedActor], classOf[MyStatelessTypedActorImpl], 1000)
      typedActor1.requestReply("hello") should equal("world")
      typedActor1.requestReply("hello") should equal("world")

      val bytes = toBinaryJ(typedActor1, MyTypedStatelessActorFormat)
      val typedActor2: MyTypedActor = fromBinaryJ(bytes, MyTypedStatelessActorFormat)
      typedActor2.requestReply("hello") should equal("world")
    }

    it("should be able to serialize and de-serialize a stateful typed actor") {
      val typedActor1 = TypedActor.newInstance(classOf[MyTypedActor], classOf[MyTypedActorImpl], 1000)
      typedActor1.requestReply("hello") should equal("world 1")
      typedActor1.requestReply("scala") should equal("hello scala 2")

      val f = new MyTypedActorFormat
      val bytes = toBinaryJ(typedActor1, f)
      val typedActor2: MyTypedActor = fromBinaryJ(bytes, f)
      typedActor2.requestReply("hello") should equal("world 3")
    }

    it("should be able to serialize and de-serialize a stateful typed actor with compound state") {
      val typedActor1 = TypedActor.newInstance(classOf[MyTypedActor], classOf[MyTypedActorWithDualCounter], 1000)
      typedActor1.requestReply("hello") should equal("world 1 1")
      typedActor1.requestReply("hello") should equal("world 2 2")

      val f = new MyTypedActorWithDualCounterFormat
      val bytes = toBinaryJ(typedActor1, f)
      val typedActor2: MyTypedActor = fromBinaryJ(bytes, f)
      typedActor2.requestReply("hello") should equal("world 3 3")
    }

    it("should be able to serialize a local yped actor ref to a remote typed actor ref proxy") {
      val typedActor1 = TypedActor.newInstance(classOf[MyTypedActor], classOf[MyStatelessTypedActorImpl], 1000)
      typedActor1.requestReply("hello") should equal("world")
      typedActor1.requestReply("hello") should equal("world")

      val bytes = RemoteTypedActorSerialization.toBinary(typedActor1)
      val typedActor2: MyTypedActor = RemoteTypedActorSerialization.fromBinaryToRemoteTypedActorRef(bytes)
      typedActor1.requestReply("hello") should equal("world")
    }
  }
}


trait MyTypedActor {
  def requestReply(s: String) : String
  def oneWay() : Unit
}

class MyTypedActorImpl extends TypedActor with MyTypedActor {
  var count = 0

  override def oneWay() {
    println("got oneWay message")
  }

  override def requestReply(message: String) : String = {
    count = count + 1
    if (message == "hello") {
      "world " + count
    } else ("hello " + message + " " + count)
  }
}

class MyTypedActorWithDualCounter extends TypedActor with MyTypedActor {
  var count1 = 0
  var count2 = 0

  override def oneWay() {
    println("got oneWay message")
  }

  override def requestReply(message: String) : String = {
    count1 = count1 + 1
    count2 = count2 + 1

    if (message == "hello") {
      "world " + count1 + " " + count2
    } else ("hello " + message + " " + count1 + " " + count2)
  }
}

class MyStatelessTypedActorImpl extends TypedActor with MyTypedActor {

  override def oneWay() {
    println("got oneWay message")
  }

  override def requestReply(message: String) : String = {
    if (message == "hello") "world" else ("hello " + message)
  }
}
