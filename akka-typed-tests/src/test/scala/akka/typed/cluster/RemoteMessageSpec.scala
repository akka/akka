/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import java.nio.charset.StandardCharsets

import akka.Done
import akka.testkit.AkkaSpec
import akka.typed.{ ActorRef, ActorSystem }
import akka.typed.scaladsl.Actor
import akka.actor.{ ExtendedActorSystem, ActorSystem ⇒ UntypedActorSystem }
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import com.typesafe.config.ConfigFactory
import scala.concurrent.Promise
import akka.typed.scaladsl.adapter._

class PingSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  override def identifier = 41
  override def manifest(o: AnyRef) = "a"
  override def toBinary(o: AnyRef) = o match {
    case RemoteMessageSpec.Ping(who) ⇒
      ActorRefResolver(system.toTyped).toSerializationFormat(who).getBytes(StandardCharsets.UTF_8)
  }
  override def fromBinary(bytes: Array[Byte], manifest: String) = {
    val str = new String(bytes, StandardCharsets.UTF_8)
    val ref = ActorRefResolver(system.toTyped).resolveActorRef[String](str)
    RemoteMessageSpec.Ping(ref)
  }
}

object RemoteMessageSpec {
  def config = ConfigFactory.parseString(
    s"""
    akka {
      loglevel = debug
      actor {
        provider = cluster
        warn-about-java-serializer-usage = off
        serialize-creators = off
        serializers {
          test = "akka.typed.cluster.PingSerializer"
        }
        serialization-bindings {
          "akka.typed.cluster.RemoteMessageSpec$$Ping" = test
        }
      }
      remote.artery {
        enabled = on
        canonical {
          hostname = 127.0.0.1
          port = 0
        }
      }
    }
    """)

  case class Ping(sender: ActorRef[String])
}

class RemoteMessageSpec extends AkkaSpec(RemoteMessageSpec.config) {

  import RemoteMessageSpec._

  val typedSystem = system.toTyped

  "the adapted system" should {

    "something something" in {

      val pingPromise = Promise[Done]()
      val ponger = Actor.immutable[Ping]((_, msg) ⇒
        msg match {
          case Ping(sender) ⇒
            pingPromise.success(Done)
            sender ! "pong"
            Actor.stopped
        })

      // typed actor on system1
      val pingPongActor = system.spawn(ponger, "pingpong")

      val system2 = UntypedActorSystem(system.name + "-system2", RemoteMessageSpec.config)
      val typedSystem2 = system2.toTyped
      try {

        // resolve the actor from node2
        val remoteRefStr = ActorRefResolver(typedSystem).toSerializationFormat(pingPongActor)
        val remoteRef: ActorRef[Ping] =
          ActorRefResolver(typedSystem2).resolveActorRef[Ping](remoteRefStr)

        val pongPromise = Promise[Done]()
        val recipient = system2.spawn(Actor.immutable[String] { (_, msg) ⇒
          pongPromise.success(Done)
          Actor.stopped
        }, "recipient")
        remoteRef ! Ping(recipient)

        pingPromise.future.futureValue should ===(Done)
        pongPromise.future.futureValue should ===(Done)

      } finally {
        system2.terminate()
      }
    }

  }

}
