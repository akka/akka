/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.typed.internal.receptionist

import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.actor.typed.{ ActorRef, ActorRefResolver, TypedAkkaSpecWithShutdown }
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.Cluster
import akka.serialization.SerializerWithStringManifest
import akka.testkit.typed.{ TestKit, TestKitSettings }
import akka.testkit.typed.scaladsl.TestProbe
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
          test = "akka.cluster.typed.internal.receptionist.ClusterReceptionistSpec$$PingSerializer"
        }
        serialization-bindings {
          "akka.cluster.typed.internal.receptionist.ClusterReceptionistSpec$$Ping" = test
          "akka.cluster.typed.internal.receptionist.ClusterReceptionistSpec$$Pong$$" = test
          "akka.cluster.typed.internal.receptionist.ClusterReceptionistSpec$$Perish$$" = test
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

  case object Pong
  trait PingProtocol
  case class Ping(respondTo: ActorRef[Pong.type]) extends PingProtocol
  case object Perish extends PingProtocol

  val pingPongBehavior = Behaviors.immutable[PingProtocol] { (_, msg) ⇒
    msg match {
      case Ping(respondTo) ⇒
        respondTo ! Pong
        Behaviors.same

      case Perish ⇒
        Behaviors.stopped
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

  val PingKey = ServiceKey[PingProtocol]("pingy")
}

class ClusterReceptionistSpec extends TestKit("ClusterReceptionistSpec", ClusterReceptionistSpec.config)
  with TypedAkkaSpecWithShutdown {

  import ClusterReceptionistSpec._

  implicit val testSettings = TestKitSettings(system)
  val untypedSystem1 = ActorSystemAdapter.toUntyped(system)
  val clusterNode1 = Cluster(untypedSystem1)

  val system2 = akka.actor.ActorSystem(
    system.name,
    system.settings.config)
  val adaptedSystem2 = system2.toTyped
  val clusterNode2 = Cluster(system2)

  clusterNode1.join(clusterNode1.selfAddress)
  clusterNode2.join(clusterNode1.selfAddress)

  import Receptionist._

  "The cluster receptionist" must {

    "must eventually replicate registrations to the other side" in {
      val regProbe = TestProbe[Any]()(system)
      val regProbe2 = TestProbe[Any]()(adaptedSystem2)

      adaptedSystem2.receptionist ! Subscribe(PingKey, regProbe2.ref)
      regProbe2.expectMsg(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

      val service = spawn(pingPongBehavior)
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

  override def afterAll(): Unit = {
    super.afterAll()
    Await.result(system.terminate(), 3.seconds)
    Await.result(system2.terminate(), 3.seconds)
  }
}
