/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.scaladsl.adapter

import java.nio.charset.StandardCharsets

import akka.Done
import akka.testkit.AkkaSpec
import akka.typed.{ ActorRef, ActorSystem }
import akka.typed.scaladsl.Actor
import akka.actor.{ ExtendedActorSystem, ActorSystem ⇒ UntypedActorSystem }
import akka.cluster.Cluster
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import com.typesafe.config.ConfigFactory

import scala.concurrent.Promise
import akka.typed.cluster.ActorRefResolver
import akka.typed.internal.adapter.ActorRefAdapter

class PingSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  override def identifier = 41
  override def manifest(o: AnyRef) = "a"
  override def toBinary(o: AnyRef) = o match {
    case RemotingSpec.Ping(who) ⇒
      ActorRefResolver(system.toTyped).toSerializationFormat(who).getBytes(StandardCharsets.UTF_8)
  }
  override def fromBinary(bytes: Array[Byte], manifest: String) = {
    val str = new String(bytes, StandardCharsets.UTF_8)
    val ref = ActorRefResolver(system.toTyped).resolveActorRef[String](str)
    RemotingSpec.Ping(ref)
  }
}

object RemotingSpec {
  def config = ConfigFactory.parseString(
    s"""
    akka {
      loglevel = debug
      actor {
        provider = cluster
        warn-about-java-serializer-usage = off
        serialize-creators = off
        serializers {
          test = "akka.typed.scaladsl.adapter.PingSerializer"
        }
        serialization-bindings {
          "akka.typed.scaladsl.adapter.RemotingSpec$$Ping" = test
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

class RemotingSpec extends AkkaSpec(RemotingSpec.config) {

  import RemotingSpec._

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

      val system2 = UntypedActorSystem(system.name + "-system2", RemotingSpec.config)
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
