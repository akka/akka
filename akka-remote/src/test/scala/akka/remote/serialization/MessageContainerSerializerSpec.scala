/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory
import akka.testkit.AkkaSpec
import akka.actor.ActorSelectionMessage
import akka.actor.SelectChildName
import akka.actor.SelectParent
import akka.actor.SelectChildPattern
import akka.util.Helpers

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MessageContainerSerializerSpec extends AkkaSpec {

  val ser = SerializationExtension(system)

  "DaemonMsgCreateSerializer" must {

    "resolve serializer for ActorSelectionMessage" in {
      ser.serializerFor(classOf[ActorSelectionMessage]).getClass should be(classOf[MessageContainerSerializer])
    }

    "serialize and de-serialize ActorSelectionMessage" in {
      verifySerialization(ActorSelectionMessage("hello", Vector(
        SelectChildName("user"), SelectChildName("a"), SelectChildName("b"), SelectParent,
        SelectChildPattern("*"), SelectChildName("c")), wildcardFanOut = true))
    }

    def verifySerialization(msg: AnyRef): Unit = {
      ser.deserialize(ser.serialize(msg).get, msg.getClass).get should be(msg)
    }

  }
}

