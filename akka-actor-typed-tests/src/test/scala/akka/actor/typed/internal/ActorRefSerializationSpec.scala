/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.ActorRef
import akka.serialization.{ JavaSerializer, SerializationExtension }
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object ActorRefSerializationSpec {
  def config = ConfigFactory.parseString(
    """
      akka.actor {
        serialize-messages = off
        allow-java-serialization = true
      }
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
    """)

  case class MessageWrappingActorRef(s: String, ref: ActorRef[Unit]) extends java.io.Serializable
}

class ActorRefSerializationSpec extends ScalaTestWithActorTestKit(ActorRefSerializationSpec.config) with WordSpecLike {

  val serialization = SerializationExtension(system.toUntyped)

  "ActorRef[T]" must {
    "be serialized and deserialized by MiscMessageSerializer" in {
      val obj = spawn(Behaviors.empty[Unit])
      serialization.findSerializerFor(obj) match {
        case serializer: MiscMessageSerializer ⇒
          val blob = serializer.toBinary(obj)
          val ref = serializer.fromBinary(blob, serializer.manifest(obj))
          ref should ===(obj)
        case s ⇒
          throw new IllegalStateException(s"Wrong serializer ${s.getClass} for ${obj.getClass}")
      }
    }

    "be serialized and deserialized by JavaSerializer inside another java.io.Serializable message" in {
      val ref = spawn(Behaviors.empty[Unit])
      val obj = ActorRefSerializationSpec.MessageWrappingActorRef("some message", ref)

      serialization.findSerializerFor(obj) match {
        case serializer: JavaSerializer ⇒
          val blob = serializer.toBinary(obj)
          val restored = serializer.fromBinary(blob, None)
          restored should ===(obj)
        case s ⇒
          throw new IllegalStateException(s"Wrong serializer ${s.getClass} for ${obj.getClass}")
      }
    }
  }
}
