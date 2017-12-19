/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.typed.internal

import akka.serialization.{ SerializationExtension, SerializerWithStringManifest }
import akka.actor.typed.{ ActorRef, TypedSpec }
import akka.actor.typed.TypedSpec.Create
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import com.typesafe.config.ConfigFactory

object MiscMessageSerializerSpec {
  def config = ConfigFactory.parseString(
    """
      akka.actor {
        provider = cluster
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
      val ref = (system ? Create(Actor.empty[Unit], "some-actor")).futureValue
      checkSerialization(ref)
    }
  }
}
