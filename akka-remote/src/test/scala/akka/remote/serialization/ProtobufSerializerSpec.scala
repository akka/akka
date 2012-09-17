/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec
import akka.remote.RemoteProtocol.MessageProtocol
import akka.remote.ProtobufProtocol.MyMessage

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ProtobufSerializerSpec extends AkkaSpec {

  val ser = SerializationExtension(system)

  "Serialization" must {

    "resolve protobuf serializer" in {
      ser.serializerFor(classOf[MessageProtocol]).getClass must be(classOf[ProtobufSerializer])
      ser.serializerFor(classOf[MyMessage]).getClass must be(classOf[ProtobufSerializer])
    }

  }
}

