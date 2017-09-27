/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.internal

import akka.serialization.{ SerializationExtension, SerializerWithStringManifest }
import akka.typed.{ ActorRef, TypedSpec }
import akka.typed.TypedSpec.Create
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.AskPattern._
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

  object `The typed MiscMessageSerializer` {

    def `must serialize and deserialize typed actor refs `(): Unit = {

      val ref = (system ? Create(Actor.empty[Unit], "some-actor")).futureValue

      val serialization = SerializationExtension(ActorSystemAdapter.toUntyped(system))

      val serializer = serialization.findSerializerFor(ref) match {
        case s: SerializerWithStringManifest ⇒ s
      }

      val manifest = serializer.manifest(ref)
      val serialized = serializer.toBinary(ref)

      val result = serializer.fromBinary(serialized, manifest)

      result should ===(ref)

    }
  }

}
