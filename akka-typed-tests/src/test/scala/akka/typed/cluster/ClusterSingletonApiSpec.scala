/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import akka.typed.{ ActorRef, Props, TypedSpec }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._

object ClusterSingletonApiSpec {

  val config = ConfigFactory.parseString(
    s"""
      akka.actor {
        provider = cluster
        serialize-messages = off
        allow-java-serialization = off

        serializers {
          test = "akka.typed.cluster.ClusterSingletonApiSpec$$PingSerializer"
        }
        serialization-bindings {
          "akka.typed.cluster.ClusterSingletonApiSpec$$Ping" = test
          "akka.typed.cluster.ClusterSingletonApiSpec$$Pong$$" = test
          "akka.typed.cluster.ClusterSingletonApiSpec$$Perish$$" = test
        }
      }
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.enabled = true
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
}

class ClusterSingletonApiSpec extends TypedSpec(ClusterSingletonApiSpec.config) with ScalaFutures {
  import ClusterSingletonApiSpec._

  implicit val testSettings = TestKitSettings(system)
  val clusterNode1 = Cluster(system)
  val untypedSystem1 = system.toUntyped

  val system2 = akka.actor.ActorSystem(
    system.name,
    ConfigFactory.parseString(
      """
        akka.cluster.roles = ["singleton"]
      """).withFallback(system.settings.config))
  val adaptedSystem2 = system2.toTyped
  val clusterNode2 = Cluster(adaptedSystem2)

  object `A typed cluster singleton` {

    def `01 must be accessible from two nodes in a cluster`() = {
      val node1UpProbe = TestProbe[SelfUp]()(system, implicitly[TestKitSettings])
      clusterNode1.subscriptions ! Subscribe(node1UpProbe.ref, classOf[SelfUp])

      val node2UpProbe = TestProbe[SelfUp]()(adaptedSystem2, implicitly[TestKitSettings])
      clusterNode1.subscriptions ! Subscribe(node2UpProbe.ref, classOf[SelfUp])

      clusterNode1.manager ! Join(clusterNode1.selfMember.address)
      clusterNode2.manager ! Join(clusterNode1.selfMember.address)

      node1UpProbe.expectMsgType[SelfUp]
      node2UpProbe.expectMsgType[SelfUp]

      val cs1 = ClusterSingleton(system)
      val cs2 = ClusterSingleton(adaptedSystem2)

      val settings = ClusterSingletonSettings(system).withRole("singleton")
      val node1ref = cs1.spawn(pingPong, "ping-pong", Props.empty, settings, Perish)
      val node2ref = cs2.spawn(pingPong, "ping-pong", Props.empty, settings, Perish)

      // subsequent spawning returns the same refs
      cs1.spawn(pingPong, "ping-pong", Props.empty, settings, Perish) should ===(node1ref)
      cs2.spawn(pingPong, "ping-pong", Props.empty, settings, Perish) should ===(node2ref)

      val node1PongProbe = TestProbe[Pong.type]()(system, implicitly[TestKitSettings])
      val node2PongProbe = TestProbe[Pong.type]()(adaptedSystem2, implicitly[TestKitSettings])

      node1PongProbe.awaitAssert({
        node1ref ! Ping(node1PongProbe.ref)
        node1PongProbe.expectMsg(Pong)
      }, 3.seconds)

      node2PongProbe.awaitAssert({
        node2ref ! Ping(node2PongProbe.ref)
        node2PongProbe.expectMsg(Pong)
      }, 3.seconds)

    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    Await.result(system2.terminate(), 3.seconds)
  }

}
