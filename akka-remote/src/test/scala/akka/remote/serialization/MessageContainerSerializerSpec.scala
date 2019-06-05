/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec
import akka.actor.ActorSelectionMessage
import akka.actor.SelectChildName
import akka.actor.SelectParent
import akka.actor.SelectChildPattern

class MessageContainerSerializerSpec extends AkkaSpec {

  val ser = SerializationExtension(system)

  "DaemonMsgCreateSerializer" must {

    "resolve serializer for ActorSelectionMessage" in {
      ser.serializerFor(classOf[ActorSelectionMessage]).getClass should ===(classOf[MessageContainerSerializer])
    }

    "serialize and de-serialize ActorSelectionMessage" in {
      verifySerialization(
        ActorSelectionMessage(
          "hello",
          Vector(
            SelectChildName("user"),
            SelectChildName("a"),
            SelectChildName("b"),
            SelectParent,
            SelectChildPattern("*"),
            SelectChildName("c")),
          wildcardFanOut = true))
    }

    def verifySerialization(msg: AnyRef): Unit = {
      ser.deserialize(ser.serialize(msg).get, msg.getClass).get should ===(msg)
    }

  }
}
