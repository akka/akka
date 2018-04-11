/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.actor.typed.{ ActorRef, ActorRefResolver, TypedAkkaSpecWithShutdown }
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.typed.Cluster
import akka.serialization.SerializerWithStringManifest
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

import akka.cluster.typed.Join

object ClusterReceptionistSpec {
  val config = ConfigFactory.parseString(
    s"""
      akka.loglevel = DEBUG
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
      akka.cluster {
        auto-down-unreachable-after = 0s
        jmx.multi-mbeans-in-same-jvm = on
      }
    """)

  case object Pong
  trait PingProtocol
  case class Ping(respondTo: ActorRef[Pong.type]) extends PingProtocol
  case object Perish extends PingProtocol

  val pingPongBehavior = Behaviors.receive[PingProtocol] { (_, msg) ⇒
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

class ClusterReceptionistSpec extends ActorTestKit
  with TypedAkkaSpecWithShutdown {

  override def config = ClusterReceptionistSpec.config

  import ClusterReceptionistSpec._

  implicit val testSettings = TestKitSettings(system)
  val clusterNode1 = Cluster(system)

  val testKit2 = new ActorTestKit {
    override def name = ClusterReceptionistSpec.this.system.name
    override def config = ClusterReceptionistSpec.this.system.settings.config
  }
  val system2 = testKit2.system
  val clusterNode2 = Cluster(system2)

  clusterNode1.manager ! Join(clusterNode1.selfMember.address)
  clusterNode2.manager ! Join(clusterNode1.selfMember.address)

  import Receptionist._

  "The cluster receptionist" must {

    "must eventually replicate registrations to the other side" in {
      val regProbe = TestProbe[Any]()(system)
      val regProbe2 = TestProbe[Any]()(system2)

      system2.receptionist ! Subscribe(PingKey, regProbe2.ref)
      regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

      val service = spawn(pingPongBehavior)
      system.receptionist ! Register(PingKey, service, regProbe.ref)
      regProbe.expectMessage(Registered(PingKey, service))

      val PingKey.Listing(remoteServiceRefs) = regProbe2.expectMessageType[Listing]
      val theRef = remoteServiceRefs.head
      theRef ! Ping(regProbe2.ref)
      regProbe2.expectMessage(Pong)

      service ! Perish
      regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
    }

    "must remove registrations when node dies" in {

      val regProbe = TestProbe[Any]()(system)
      val regProbe2 = TestProbe[Any]()(system2)

      system.receptionist ! Subscribe(PingKey, regProbe.ref)
      regProbe.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

      val service2 = testKit2.spawn(pingPongBehavior)
      system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
      regProbe2.expectMessage(Registered(PingKey, service2))

      val remoteServiceRefs = regProbe.expectMessageType[Listing].serviceInstances(PingKey)
      val theRef = remoteServiceRefs.head
      theRef ! Ping(regProbe.ref)
      regProbe.expectMessage(Pong)

      // abrupt termination
      system2.terminate()
      regProbe.expectMessage(10.seconds, Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
    }

  }

  override def afterAll(): Unit = {
    super.afterAll()
    ActorTestKit.shutdown(system2, 10.seconds)
  }
}
