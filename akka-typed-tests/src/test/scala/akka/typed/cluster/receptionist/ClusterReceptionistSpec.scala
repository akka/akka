/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.receptionist

import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.cluster.Cluster
import akka.serialization.SerializerWithStringManifest
import akka.typed.ActorRef
import akka.typed.ActorSystem
import akka.typed.TypedSpec
import akka.typed.TypedSpec.Command
import akka.typed.cluster.ActorRefResolver
import akka.typed.internal.adapter.ActorRefAdapter
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.internal.receptionist.ReceptionistImpl
import akka.typed.receptionist.Receptionist
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._

object ClusterReceptionistSpec {
  val config = ConfigFactory.parseString(
    s"""
      akka.actor {
        provider = cluster
        serialize-messages = off
        allow-java-serialization = true
        serializers {
          test = "akka.typed.cluster.receptionist.ClusterReceptionistSpec$$PingSerializer"
        }
        serialization-bindings {
          "akka.typed.cluster.receptionist.ClusterReceptionistSpec$$Ping" = test
          "akka.typed.cluster.receptionist.ClusterReceptionistSpec$$Pong$$" = test
          "akka.typed.cluster.receptionist.ClusterReceptionistSpec$$Perish$$" = test
          # for now, using Java serializers is good enough (tm), see #23687
          # "akka.typed.internal.receptionist.ReceptionistImpl$$DefaultServiceKey" = test
        }
      }
      akka.remote.artery.enabled = true
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1
      akka.cluster.jmx.multi-mbeans-in-same-jvm = on
    """)

  trait PingProtocol
  case object Pong
  case class Ping(respondTo: ActorRef[Pong.type]) extends PingProtocol

  case object Perish extends PingProtocol

  val pingPong = Actor.immutable[PingProtocol] { (ctx, msg) ⇒

    msg match {
      case Ping(respondTo) ⇒
        respondTo ! Pong
        Actor.same

      case Perish ⇒
        Actor.stopped
    }

  }

  class PingSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
    def identifier: Int = 47
    def manifest(o: AnyRef): String = o match {
      case _: Ping ⇒ "a"
      case Pong    ⇒ "b"
      case Perish  ⇒ "c"
    }

    def toBinary(o: AnyRef): Array[Byte] = o match {
      case p: Ping ⇒ ActorRefResolver(system.toTyped).toSerializationFormat(p.respondTo).getBytes(StandardCharsets.UTF_8)
      case Pong    ⇒ Array.emptyByteArray
      case Perish  ⇒ Array.emptyByteArray
    }

    def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
      case "a" ⇒ Ping(ActorRefResolver(system.toTyped).resolveActorRef(new String(bytes, StandardCharsets.UTF_8)))
      case "b" ⇒ Pong
      case "c" ⇒ Perish
    }
  }

  val PingKey = Receptionist.ServiceKey[PingProtocol]("pingy")
}

class ClusterReceptionistSpec extends TypedSpec(ClusterReceptionistSpec.config) {
  import ClusterReceptionistSpec._

  val adaptedSystem = system
  implicit val testSettings = TestKitSettings(adaptedSystem)
  val untypedSystem1 = ActorSystemAdapter.toUntyped(adaptedSystem)
  val clusterNode1 = Cluster(untypedSystem1)

  val system2 = akka.actor.ActorSystem(
    adaptedSystem.name,
    adaptedSystem.settings.config)
  val adaptedSystem2 = system2.toTyped
  val clusterNode2 = Cluster(system2)

  clusterNode1.join(clusterNode1.selfAddress)
  clusterNode2.join(clusterNode1.selfAddress)

  object `The ClusterReceptionist` extends StartSupport {
    def system: ActorSystem[Command] = adaptedSystem
    import Receptionist._

    def `must eventually replicate registrations to the other side`() = new TestSetup {
      val regProbe = TestProbe[Any]()(adaptedSystem, testSettings)
      val regProbe2 = TestProbe[Any]()(adaptedSystem2, testSettings)

      adaptedSystem2.receptionist ! Subscribe(PingKey, regProbe2.ref)
      regProbe2.expectMsg(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

      val service = start(pingPong)
      system.receptionist ! Register(PingKey, service, regProbe.ref)
      regProbe.expectMsg(Registered(PingKey, service))

      val Listing(PingKey, remoteServiceRefs) = regProbe2.expectMsgType[Listing[PingProtocol]]
      val theRef = remoteServiceRefs.head
      theRef ! Ping(regProbe2.ref)
      regProbe2.expectMsg(Pong)

      service ! Perish
      regProbe2.expectMsg(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
    }
  }

  trait TestSetup {
  }

  override def afterAll(): Unit = {
    super.afterAll()
    Await.result(adaptedSystem.terminate(), 3.seconds)
    Await.result(system2.terminate(), 3.seconds)
  }
}
