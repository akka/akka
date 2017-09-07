package akka.typed.scaladsl.adapter

import java.nio.charset.StandardCharsets

import akka.Done
import akka.testkit.AkkaSpec
import akka.typed.{ ActorRef, ActorSystem }
import akka.typed.scaladsl.Actor
import akka.actor.{ ExtendedActorSystem, ActorSystem ⇒ UntypedActorSystem }
import akka.cluster.{ Cluster, ClusterActorRefProvider }
import akka.remote.RARP
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import akka.typed.cluster.TypedClusterActorRefProvider
import com.typesafe.config.ConfigFactory

import scala.concurrent.Promise

class PingSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  override def identifier = 41
  override def manifest(o: AnyRef) = "a"
  override def toBinary(o: AnyRef) = o match {
    case RemotingSpec.Ping(who) ⇒
      who.path.toSerializationFormatWithAddress(Cluster(system).selfAddress).getBytes(StandardCharsets.UTF_8)
  }
  override def fromBinary(bytes: Array[Byte], manifest: String) = {
    val str = new String(bytes, StandardCharsets.UTF_8)
    val ref = system.provider.asInstanceOf[TypedClusterActorRefProvider].resolveTypedActorRef[String](str)
    println(s"fromBinary: ${ref}")
    RemotingSpec.Ping(ref)
  }
}

object RemotingSpec {
  def config = ConfigFactory.parseString(
    """
    akka {
      loglevel = debug
      actor {
        provider = akka.typed.cluster.TypedClusterActorRefProvider
        warn-about-java-serializer-usage = off
        serialize-creators = off
        serializers {
          test = "akka.typed.scaladsl.adapter.PingSerializer"
        }
        serialization-bindings {
          "akka.typed.scaladsl.adapter.RemotingSpec$Ping" = test
        }
      }
      remote.artery {
        enabled = on
        canonical {
          hostname = 127.0.0.1
          port = 2551
        }
      }
    }
    """)

  case class Ping(sender: ActorRef[String])
}

class RemotingSpec extends AkkaSpec(RemotingSpec.config) {

  import RemotingSpec._

  "the adapted system" should {

    "something something" in {

      val pingPromise = Promise[Done]()
      val ponger = Actor.immutable[Ping]((_, msg) ⇒
        msg match {
          case Ping(sender) ⇒
            pingPromise.success(Done)
            sender ! "pong"
            Actor.stopped
        }
      )

      // typed actor on system1
      val pingPongActor = system.spawn(ponger, "pingpong")

      val system2 = UntypedActorSystem(system.name + "-system2", ConfigFactory.parseString("akka.remote.artery.canonical.port=2552").withFallback(RemotingSpec.config))
      try {
        val provider2 = system2.asInstanceOf[ExtendedActorSystem]
          .provider.asInstanceOf[TypedClusterActorRefProvider]

        // resolve the actor from node2
        val remoteRef: ActorRef[Ping] =
          provider2.resolveTypedActorRef[Ping](s"akka://${system.name}@127.0.0.1:2551/user/pingpong")

        val pongPromise = Promise[Done]()
        val recipient = system2.spawn(Actor.immutable[String] { (_, msg) ⇒
          pongPromise.success(Done)
          Actor.stopped
        }, "recipient")
        println(s"recipient actor: ${recipient.path} ${recipient.path.uid}")
        remoteRef ! Ping(recipient)

        pingPromise.future.futureValue should ===(Done)
        pongPromise.future.futureValue should ===(Done)

      } finally {
        system2.terminate()
      }
    }

  }

}
