/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import java.nio.charset.StandardCharsets

import akka.Done
import akka.testkit.AkkaSpec
import akka.actor.typed.{ ActorRef, ActorRefResolver }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.{ ExtendedActorSystem, ActorSystem => UntypedActorSystem }
import akka.serialization.SerializerWithStringManifest
import com.typesafe.config.ConfigFactory

import scala.concurrent.Promise
import akka.actor.typed.scaladsl.adapter._

class PingSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  override def identifier = 41
  override def manifest(o: AnyRef) = "a"
  override def toBinary(o: AnyRef) = o match {
    case RemoteMessageSpec.Ping(who) =>
      ActorRefResolver(system.toTyped).toSerializationFormat(who).getBytes(StandardCharsets.UTF_8)
  }
  override def fromBinary(bytes: Array[Byte], manifest: String) = {
    val str = new String(bytes, StandardCharsets.UTF_8)
    val ref = ActorRefResolver(system.toTyped).resolveActorRef[String](str)
    RemoteMessageSpec.Ping(ref)
  }
}

object RemoteMessageSpec {
  def config = ConfigFactory.parseString(s"""
    akka {
      loglevel = debug
      actor {
        provider = cluster
        warn-about-java-serializer-usage = off
        serialize-creators = off
        serializers {
          test = "akka.cluster.typed.PingSerializer"
        }
        serialization-bindings {
          "akka.cluster.typed.RemoteMessageSpec$$Ping" = test
        }
      }
      remote.netty.tcp.port = 0
      remote.artery {
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
      val ponger = Behaviors.receive[Ping]((_, msg) =>
        msg match {
          case Ping(sender) =>
            pingPromise.success(Done)
            sender ! "pong"
            Behaviors.stopped
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
        val recipient = system2.spawn(Behaviors.receive[String] { (_, _) =>
          pongPromise.success(Done)
          Behaviors.stopped
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
