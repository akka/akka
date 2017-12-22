/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.cluster.sharding.typed

import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{Eventually, ScalaFutures}

import scala.concurrent.duration._

object ClusterShardingSpec {
  val config = ConfigFactory.parseString(
    s"""
      akka.actor.provider = cluster

      // akka.loglevel = debug

      akka.remote.artery.enabled = true
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.cluster.jmx.multi-mbeans-in-same-jvm = on

      akka.coordinated-shutdown.terminate-actor-system = off

      akka.actor {
        serialize-messages = off
        allow-java-serialization = off

       serializers {
          test = "akka.cluster.sharding.typed.ClusterShardingSpec$$Serializer"
        }
        serialization-bindings {
          "akka.cluster.sharding.typed.ClusterShardingSpec$$TestProtocol" = test
          "akka.cluster.sharding.typed.ClusterShardingSpec$$IdTestProtocol" = test
        }
      }
    """.stripMargin)

  sealed trait TestProtocol extends java.io.Serializable
  final case class ReplyPlz(toMe: ActorRef[String]) extends TestProtocol
  final case class WhoAreYou(replyTo: ActorRef[String]) extends TestProtocol
  final case class StopPlz() extends TestProtocol

  sealed trait IdTestProtocol extends java.io.Serializable {
    def id: String
  }
  final case class IdReplyPlz(id: String, toMe: ActorRef[String]) extends IdTestProtocol
  final case class IdWhoAreYou(id: String, replyTo: ActorRef[String]) extends IdTestProtocol
  final case class IdStopPlz(id: String) extends IdTestProtocol

  class Serializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
    def identifier: Int = 48
    def manifest(o: AnyRef): String = o match {
      case _: ReplyPlz    ⇒ "a"
      case _: WhoAreYou   ⇒ "b"
      case _: StopPlz     ⇒ "c"
      case _: IdReplyPlz  ⇒ "A"
      case _: IdWhoAreYou ⇒ "B"
      case _: IdStopPlz   ⇒ "C"
    }

    private def actorRefToBinary(ref: ActorRef[_]): Array[Byte] =
      ActorRefResolver(system.toTyped).toSerializationFormat(ref).getBytes(StandardCharsets.UTF_8)

    private def idAndRefToBinary(id: String, ref: ActorRef[_]): Array[Byte] = {
      val idBytes = id.getBytes(StandardCharsets.UTF_8)
      val refBytes = actorRefToBinary(ref)
      // yeah, very ad-hoc ;-)
      Array(idBytes.length.toByte) ++ idBytes ++ refBytes
    }

    def toBinary(o: AnyRef): Array[Byte] = o match {
      case ReplyPlz(ref)        ⇒ actorRefToBinary(ref)
      case WhoAreYou(ref)       ⇒ actorRefToBinary(ref)
      case _: StopPlz           ⇒ Array.emptyByteArray
      case IdReplyPlz(id, ref)  ⇒ idAndRefToBinary(id, ref)
      case IdWhoAreYou(id, ref) ⇒ idAndRefToBinary(id, ref)
      case IdStopPlz(id)        ⇒ id.getBytes(StandardCharsets.UTF_8)
    }

    private def actorRefFromBinary[T](bytes: Array[Byte]): ActorRef[T] =
      ActorRefResolver(system.toTyped).resolveActorRef(new String(bytes, StandardCharsets.UTF_8))

    private def idAndRefFromBinary[T](bytes: Array[Byte]): (String, ActorRef[T]) = {
      val idLength = bytes(0)
      val id = new String(bytes.slice(1, idLength + 1), StandardCharsets.UTF_8)
      val ref = actorRefFromBinary(bytes.drop(1 + idLength))
      (id, ref)
    }

    def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
      case "a" ⇒ ReplyPlz(actorRefFromBinary(bytes))
      case "b" ⇒ WhoAreYou(actorRefFromBinary(bytes))
      case "c" ⇒ StopPlz()
      case "A" ⇒ IdReplyPlz.tupled(idAndRefFromBinary(bytes))
      case "B" ⇒ IdWhoAreYou.tupled(idAndRefFromBinary(bytes))
      case "C" ⇒ IdStopPlz(new String(bytes, StandardCharsets.UTF_8))
    }
  }

}

class ClusterShardingSpec extends TestKit("ClusterShardingSpec", ClusterShardingSpec.config)
  with TypedAkkaSpecWithShutdown with ScalaFutures with Eventually {

  import ClusterShardingSpec._
  import akka.actor.typed.scaladsl.adapter._

  implicit val s = system
  val sharding = ClusterSharding(system)

  implicit val untypedSystem = system.toUntyped
  implicit val timeout = Timeout(1.second)

  val untypedSystem2 = akka.actor.ActorSystem(system.name, system.settings.config)
  val system2 = untypedSystem2.toTyped
  val sharding2 = ClusterSharding(system2)

  override def afterAll(): Unit = {
    system2.terminate().futureValue
    super.afterAll()
  }

  val typeKey = EntityTypeKey[TestProtocol]("envelope-shard")
  val behavior = Actor.immutable[TestProtocol] {
    case (_, StopPlz()) ⇒
      Actor.stopped

    case (ctx, WhoAreYou(replyTo)) ⇒
      replyTo ! s"I'm ${ctx.self.path.name}"
      Actor.same

    case (_, ReplyPlz(toMe)) ⇒
      toMe ! "Hello!"
      Actor.same
  }

  val typeKey2 = EntityTypeKey[IdTestProtocol]("no-envelope-shard")
  val behaviorWithId = Actor.immutable[IdTestProtocol] {
    case (_, IdStopPlz(_)) ⇒
      Actor.stopped

    case (ctx, IdWhoAreYou(_, replyTo)) ⇒
      replyTo ! s"I'm ${ctx.self.path.name}"
      Actor.same

    case (_, IdReplyPlz(_, toMe)) ⇒
      toMe ! "Hello!"
      Actor.same
  }

  "Typed cluster sharding" must {

    "join cluster" in {
      Cluster(system).manager ! Join(Cluster(system).selfMember.address)
      Cluster(system2).manager ! Join(Cluster(system).selfMember.address)

      eventually {
        Cluster(system).state.members.map(_.status) should ===(Set[MemberStatus](MemberStatus.Up))
        Cluster(system).state.members.size should ===(2)
      }
      eventually {
        Cluster(system2).state.members.map(_.status) should ===(Set[MemberStatus](MemberStatus.Up))
        Cluster(system2).state.members.size should ===(2)
      }

    }

    "send messages via cluster sharding, using envelopes" in {
      val ref = sharding.spawn(
        behavior,
        Props.empty,
        typeKey,
        ClusterShardingSettings(system),
        10,
        StopPlz())
      sharding2.spawn(
        behavior,
        Props.empty,
        typeKey,
        ClusterShardingSettings(system2),
        10,
        StopPlz())

      (1 to 10).foreach { n ⇒
        val p = TestProbe[String]()
        ref ! ShardingEnvelope(s"test$n", ReplyPlz(p.ref))
        p.expectMsg(3.seconds, "Hello!")
        ref ! ShardingEnvelope(s"test$n", StopPlz())
      }
    }

    "send messages via cluster sharding, without envelopes" in {
      val ref = sharding.spawn(
        behaviorWithId,
        Props.empty,
        typeKey2,
        ClusterShardingSettings(system),
        ShardingMessageExtractor.noEnvelope[IdTestProtocol](10, _.id),
        IdStopPlz("THE_ID_HERE"))
      sharding2.spawn(
        behaviorWithId,
        Props.empty,
        typeKey2,
        ClusterShardingSettings(system2),
        ShardingMessageExtractor.noEnvelope[IdTestProtocol](10, _.id),
        IdStopPlz("THE_ID_HERE"))

      (1 to 10).foreach { n ⇒
        val p = TestProbe[String]()
        ref ! IdReplyPlz(s"test$n", p.ref)
        p.expectMsg(3.seconds, "Hello!")
        ref ! IdStopPlz(s"test$n")
      }
    }

    //    "04 fail if starting sharding for already used typeName, but with wrong type" in  {
    //      val ex = intercept[Exception] {
    //        sharding.spawn(
    //          Actor.empty[String],
    //          Props.empty,
    //          "example-02",
    //          ClusterShardingSettings(adaptedSystem),
    //          10,
    //          "STOP"
    //        )
    //      }
    //
    //      ex.getMessage should include("already started")
    //    }

    "EntityRef - tell" in {
      val charlieRef = sharding.entityRefFor(typeKey, "charlie")

      val p = TestProbe[String]()

      charlieRef ! WhoAreYou(p.ref)
      p.expectMsg(3.seconds, "I'm charlie")

      charlieRef tell WhoAreYou(p.ref)
      p.expectMsg(3.seconds, "I'm charlie")

      charlieRef ! StopPlz()
    }

    "EntityRef - ask" in {
      val bobRef = sharding.entityRefFor(typeKey, "bob")
      val charlieRef = sharding.entityRefFor(typeKey, "charlie")

      val reply1 = bobRef ? WhoAreYou // TODO document that WhoAreYou(_) would not work
      reply1.futureValue should ===("I'm bob")

      val reply2 = charlieRef ask WhoAreYou
      reply2.futureValue should ===("I'm charlie")

      bobRef ! StopPlz()
    }
  }
}
