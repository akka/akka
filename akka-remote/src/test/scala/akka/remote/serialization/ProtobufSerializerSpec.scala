/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec
import akka.remote.WireFormats.SerializedMessage
import akka.remote.ProtobufProtocol.MyMessage
import akka.remote.MessageSerializer
import akka.actor.ExtendedActorSystem
import akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3

class ProtobufSerializerSpec extends AkkaSpec {

  val ser = SerializationExtension(system)

  "Serialization" must {

    "resolve protobuf serializer" in {
      ser.serializerFor(classOf[SerializedMessage]).getClass should ===(classOf[ProtobufSerializer])
      ser.serializerFor(classOf[MyMessage]).getClass should ===(classOf[ProtobufSerializer])
      ser.serializerFor(classOf[MyMessageV3]).getClass should ===(classOf[ProtobufSerializer])
    }

    "work for SerializedMessage (just an akka.protobuf message)" in {
      // create a protobuf message
      val protobufMessage: SerializedMessage =
        MessageSerializer.serialize(system.asInstanceOf[ExtendedActorSystem], "hello")
      // serialize it with ProtobufSerializer
      val bytes = ser.serialize(protobufMessage).get
      // deserialize the bytes with ProtobufSerializer
      val deserialized = ser.deserialize(bytes, protobufMessage.getClass).get.asInstanceOf[SerializedMessage]
      deserialized.getSerializerId should ===(protobufMessage.getSerializerId)
      deserialized.getMessage should ===(protobufMessage.getMessage) // same "hello"
    }

    "work for a serialized protobuf v3 message" in {
      val protobufV3Message: MyMessageV3 =
        MyMessageV3.newBuilder().setQuery("query1").setPageNumber(1).setResultPerPage(2).build()
      val bytes = ser.serialize(protobufV3Message).get
      val deserialized: MyMessageV3 = ser.deserialize(bytes, protobufV3Message.getClass).get
      protobufV3Message should ===(deserialized)
    }

  }
}
