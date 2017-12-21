/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.actor.typed.TypedSpec
import akka.actor.typed.TypedSpec.{ Create ⇒ TCreate }
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory

object MiscMessageSerializerSpec {
  def config = ConfigFactory.parseString(
    """
      akka.actor {
        serialize-messages = off
        allow-java-serialization = true
      }
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
    """)
}

class MiscMessageSerializerSpec extends TypedSpec(MiscMessageSerializerSpec.config) {

  val serialization = SerializationExtension(system.toUntyped)

  "MiscMessageSerializer" must {
    def checkSerialization(obj: AnyRef): Unit = {
      serialization.findSerializerFor(obj) match {
        case serializer: MiscMessageSerializer ⇒
          val blob = serializer.toBinary(obj)
          val ref = serializer.fromBinary(blob, serializer.manifest(obj))
          ref should ===(obj)
        case s ⇒
          throw new IllegalStateException(s"Wrong serializer ${s.getClass} for ${obj.getClass}")
      }
    }

    "must serialize and deserialize typed actor refs" in {
      val ref = (system ? TCreate(Actor.empty[Unit], "some-actor")).futureValue
      println(ref.getClass)
      checkSerialization(ref)
    }
  }
}
