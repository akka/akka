/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.actor.typed.receptionist.Receptionist.Registered
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorRefResolver, ActorSystem }
import akka.serialization.SerializerWithStringManifest
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class RemoteContextAskSpecSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  override def identifier = 41
  override def manifest(o: AnyRef) = o match {
    case _: RemoteContextAskSpec.Ping ⇒ "a"
    case RemoteContextAskSpec.Pong    ⇒ "b"
  }
  override def toBinary(o: AnyRef) = o match {
    case RemoteContextAskSpec.Ping(who) ⇒
      ActorRefResolver(system.toTyped).toSerializationFormat(who).getBytes(StandardCharsets.UTF_8)
    case RemoteContextAskSpec.Pong ⇒ Array.emptyByteArray
  }
  override def fromBinary(bytes: Array[Byte], manifest: String) = manifest match {
    case "a" ⇒
      val str = new String(bytes, StandardCharsets.UTF_8)
      val ref = ActorRefResolver(system.toTyped).resolveActorRef[RemoteContextAskSpec.Pong.type](str)
      RemoteContextAskSpec.Ping(ref)
    case "b" ⇒ RemoteContextAskSpec.Pong
  }
}

object RemoteContextAskSpec {
  def config = ConfigFactory.parseString(
    s"""
    akka {
      loglevel = debug
      actor {
        provider = cluster
        warn-about-java-serializer-usage = off
        serialize-creators = off
        serializers {
          test = "akka.cluster.typed.RemoteContextAskSpecSerializer"
        }
        serialization-bindings {
          "akka.cluster.typed.RemoteContextAskSpec$$Ping" = test
          "akka.cluster.typed.RemoteContextAskSpec$$Pong$$" = test
        }
      }
      remote.netty.tcp.port = 0
      remote.netty.tcp.host = 127.0.0.1
      remote.artery {
        canonical {
          hostname = 127.0.0.1
          port = 0
        }
      }
    }
  """)

  case object Pong
  case class Ping(respondTo: ActorRef[Pong.type])

  def pingPong = Behaviors.receive[Ping] { (_, msg) ⇒
    msg match {
      case Ping(sender) ⇒
        sender ! Pong
        Behaviors.same
    }
  }

  val pingPongKey = ServiceKey[Ping]("ping-pong")

}

class RemoteContextAskSpec extends ScalaTestWithActorTestKit(RemoteContextAskSpec.config) with WordSpecLike {

  import RemoteContextAskSpec._

  "Asking another actor through the ActorContext across remoting" must {

    "work" in {
      val node1 = Cluster(system)
      val node1Probe = TestProbe[AnyRef]()(system)
      node1.manager ! Join(node1.selfMember.address)

      Receptionist(system).ref ! Receptionist.Subscribe(pingPongKey, node1Probe.ref)
      node1Probe.expectMessageType[Receptionist.Listing]

      val system2 = ActorSystem(pingPong, system.name, system.settings.config)
      val node2 = Cluster(system2)
      node2.manager ! Join(node1.selfMember.address)

      val node2Probe = TestProbe[AnyRef]()(system2)
      Receptionist(system2).ref ! Receptionist.Register(pingPongKey, system2, node2Probe.ref)
      node2Probe.expectMessageType[Registered]

      // wait until the service is seen on the first node
      val remoteRef = node1Probe.expectMessageType[Receptionist.Listing].serviceInstances(pingPongKey).head

      spawn(Behaviors.setup[AnyRef] { (ctx) ⇒
        implicit val timeout: Timeout = 3.seconds

        ctx.ask(remoteRef)(Ping) {
          case Success(pong) ⇒ pong
          case Failure(ex)   ⇒ ex
        }

        Behaviors.receive { (_, msg) ⇒
          node1Probe.ref ! msg
          Behaviors.same
        }
      })

      node1Probe.expectMessageType[Pong.type]

    }

  }

}
