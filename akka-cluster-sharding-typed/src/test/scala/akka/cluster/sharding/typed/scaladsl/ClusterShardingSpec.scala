/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import java.nio.charset.StandardCharsets

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.actor.ExtendedActorSystem
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.ActorSystem
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.MemberStatus
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.ShardingMessageExtractor
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import akka.cluster.typed.Leave
import akka.pattern.AskTimeoutException
import akka.serialization.SerializerWithStringManifest
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike
import akka.util.ccompat.imm._

object ClusterShardingSpec {
  val config = ConfigFactory.parseString(s"""
      akka.actor.provider = cluster

      // akka.loglevel = debug
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.cluster.jmx.multi-mbeans-in-same-jvm = on

      akka.cluster.sharding.number-of-shards = 10

      akka.coordinated-shutdown.terminate-actor-system = off

      akka.actor {
        serialize-messages = off
        # issue #24465 missing serializer for GetShardRegionStats
        #allow-java-serialization = off

       serializers {
          test = "akka.cluster.sharding.typed.scaladsl.ClusterShardingSpec$$Serializer"
        }
        serialization-bindings {
          "akka.cluster.sharding.typed.scaladsl.ClusterShardingSpec$$TestProtocol" = test
          "akka.cluster.sharding.typed.scaladsl.ClusterShardingSpec$$IdTestProtocol" = test
        }
      }
    """)

  sealed trait TestProtocol extends java.io.Serializable
  final case class ReplyPlz(toMe: ActorRef[String]) extends TestProtocol
  final case class WhoAreYou(replyTo: ActorRef[String]) extends TestProtocol
  final case class WhoAreYou2(x: Int, replyTo: ActorRef[String]) extends TestProtocol
  final case class StopPlz() extends TestProtocol
  final case class PassivatePlz() extends TestProtocol

  sealed trait IdTestProtocol extends java.io.Serializable
  final case class IdReplyPlz(id: String, toMe: ActorRef[String]) extends IdTestProtocol
  final case class IdWhoAreYou(id: String, replyTo: ActorRef[String]) extends IdTestProtocol
  final case class IdStopPlz() extends IdTestProtocol

  class Serializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
    def identifier: Int = 48
    def manifest(o: AnyRef): String = o match {
      case _: ReplyPlz     => "a"
      case _: WhoAreYou    => "b"
      case _: StopPlz      => "c"
      case _: PassivatePlz => "d"
      case _: IdReplyPlz   => "A"
      case _: IdWhoAreYou  => "B"
      case _: IdStopPlz    => "C"
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
      case ReplyPlz(ref)        => actorRefToBinary(ref)
      case WhoAreYou(ref)       => actorRefToBinary(ref)
      case _: StopPlz           => Array.emptyByteArray
      case _: PassivatePlz      => Array.emptyByteArray
      case IdReplyPlz(id, ref)  => idAndRefToBinary(id, ref)
      case IdWhoAreYou(id, ref) => idAndRefToBinary(id, ref)
      case _: IdStopPlz         => Array.emptyByteArray
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
      case "a" => ReplyPlz(actorRefFromBinary(bytes))
      case "b" => WhoAreYou(actorRefFromBinary(bytes))
      case "c" => StopPlz()
      case "d" => PassivatePlz()
      case "A" => IdReplyPlz.tupled(idAndRefFromBinary(bytes))
      case "B" => IdWhoAreYou.tupled(idAndRefFromBinary(bytes))
      case "C" => IdStopPlz()
    }
  }

  final case class TheReply(s: String)

  val typeKey = EntityTypeKey[TestProtocol]("envelope-shard")
  val typeKey2 = EntityTypeKey[IdTestProtocol]("no-envelope-shard")

  def behavior(shard: ActorRef[ClusterSharding.ShardCommand], stopProbe: Option[ActorRef[String]] = None) =
    Behaviors
      .receive[TestProtocol] {
        case (ctx, PassivatePlz()) =>
          shard ! ClusterSharding.Passivate(ctx.self)
          Behaviors.same

        case (_, StopPlz()) =>
          stopProbe.foreach(_ ! "StopPlz")
          Behaviors.stopped

        case (ctx, WhoAreYou(replyTo)) =>
          val address = Cluster(ctx.system).selfMember.address
          replyTo ! s"I'm ${ctx.self.path.name} at ${address.host.get}:${address.port.get}"
          Behaviors.same

        case (_, ReplyPlz(toMe)) =>
          toMe ! "Hello!"
          Behaviors.same
      }
      .receiveSignal {
        case (_, PostStop) =>
          stopProbe.foreach(_ ! "PostStop")
          Behaviors.same
      }

  def behaviorWithId(shard: ActorRef[ClusterSharding.ShardCommand]) = Behaviors.receive[IdTestProtocol] {
    case (_, IdStopPlz()) =>
      Behaviors.stopped

    case (ctx, IdWhoAreYou(_, replyTo)) =>
      val address = Cluster(ctx.system).selfMember.address
      replyTo ! s"I'm ${ctx.self.path.name} at ${address.host.get}:${address.port.get}"
      Behaviors.same

    case (_, IdReplyPlz(_, toMe)) =>
      toMe ! "Hello!"
      Behaviors.same
  }

  val idTestProtocolMessageExtractor = ShardingMessageExtractor.noEnvelope[IdTestProtocol](10, IdStopPlz()) {
    case IdReplyPlz(id, _)  => id
    case IdWhoAreYou(id, _) => id
    case other              => throw new IllegalArgumentException(s"Unexpected message $other")
  }
}

class ClusterShardingSpec extends ScalaTestWithActorTestKit(ClusterShardingSpec.config) with WordSpecLike {

  import ClusterShardingSpec._

  val sharding = ClusterSharding(system)

  val system2 = ActorSystem(Behaviors.ignore[Any], name = system.name, config = system.settings.config)
  val sharding2 = ClusterSharding(system2)

  override def afterAll(): Unit = {
    ActorTestKit.shutdown(system2, 5.seconds)
    super.afterAll()
  }

  private val shardingRef1: ActorRef[ShardingEnvelope[TestProtocol]] =
    sharding.init(Entity(typeKey, ctx => behavior(ctx.shard)).withStopMessage(StopPlz()))

  private val shardingRef2 = sharding2.init(Entity(typeKey, ctx => behavior(ctx.shard)).withStopMessage(StopPlz()))

  private val shardingRef3: ActorRef[IdTestProtocol] = sharding.init(
    Entity(typeKey2, ctx => behaviorWithId(ctx.shard))
      .withMessageExtractor(ShardingMessageExtractor.noEnvelope[IdTestProtocol](10, IdStopPlz()) {
        case IdReplyPlz(id, _)  => id
        case IdWhoAreYou(id, _) => id
        case other              => throw new IllegalArgumentException(s"Unexpected message $other")
      })
      .withStopMessage(IdStopPlz()))

  private val shardingRef4 = sharding2.init(
    Entity(typeKey2, ctx => behaviorWithId(ctx.shard))
      .withMessageExtractor(idTestProtocolMessageExtractor)
      .withStopMessage(IdStopPlz()))

  def totalEntityCount1(): Int = {
    import akka.pattern.ask
    implicit val timeout: Timeout = Timeout(6.seconds)
    val statsBefore = (shardingRef1.toUntyped ? akka.cluster.sharding.ShardRegion.GetClusterShardingStats(5.seconds))
      .mapTo[akka.cluster.sharding.ShardRegion.ClusterShardingStats]
    val totalCount = statsBefore.futureValue.regions.values.flatMap(_.stats.values).sum
    totalCount
  }

  "Typed cluster sharding" must {

    "join cluster" in {
      Cluster(system).manager ! Join(Cluster(system).selfMember.address)
      Cluster(system2).manager ! Join(Cluster(system).selfMember.address)

      eventually {
        Cluster(system).state.members.unsorted.map(_.status) should ===(Set[MemberStatus](MemberStatus.Up))
        Cluster(system).state.members.size should ===(2)
      }
      eventually {
        Cluster(system2).state.members.unsorted.map(_.status) should ===(Set[MemberStatus](MemberStatus.Up))
        Cluster(system2).state.members.size should ===(2)
      }

    }

    "send messages via cluster sharding, using envelopes" in {
      (1 to 10).foreach { n =>
        val p = TestProbe[String]()
        shardingRef1 ! ShardingEnvelope(s"test$n", ReplyPlz(p.ref))
        p.expectMessage("Hello!")
      }
    }

    "send messages via cluster sharding, without envelopes" in {
      (1 to 10).foreach { n =>
        val p = TestProbe[String]()
        shardingRef3 ! IdReplyPlz(s"test$n", p.ref)
        p.expectMessage("Hello!")
      }
    }

    "be able to passivate with custom stop message" in {
      val stopProbe = TestProbe[String]()
      val p = TestProbe[String]()
      val typeKey3 = EntityTypeKey[TestProtocol]("passivate-test")

      val shardingRef3: ActorRef[ShardingEnvelope[TestProtocol]] =
        sharding.init(Entity(typeKey3, ctx => behavior(ctx.shard, Some(stopProbe.ref))).withStopMessage(StopPlz()))

      shardingRef3 ! ShardingEnvelope(s"test1", ReplyPlz(p.ref))
      p.expectMessage("Hello!")

      shardingRef3 ! ShardingEnvelope(s"test1", PassivatePlz())
      stopProbe.expectMessage("StopPlz")
      stopProbe.expectMessage("PostStop")

      shardingRef3 ! ShardingEnvelope(s"test1", ReplyPlz(p.ref))
      p.expectMessage("Hello!")
    }

    "be able to passivate with PoisonPill" in {
      val stopProbe = TestProbe[String]()
      val p = TestProbe[String]()
      val typeKey4 = EntityTypeKey[TestProtocol]("passivate-test-poison")

      val shardingRef4: ActorRef[ShardingEnvelope[TestProtocol]] =
        sharding.init(Entity(typeKey4, ctx => behavior(ctx.shard, Some(stopProbe.ref))))
      // no StopPlz stopMessage

      shardingRef4 ! ShardingEnvelope(s"test4", ReplyPlz(p.ref))
      p.expectMessage("Hello!")

      shardingRef4 ! ShardingEnvelope(s"test4", PassivatePlz())
      // no StopPlz
      stopProbe.expectMessage("PostStop")

      shardingRef4 ! ShardingEnvelope(s"test4", ReplyPlz(p.ref))
      p.expectMessage("Hello!")
    }

    "fail if init sharding for already used typeName, but with a different type" in {
      // sharding has been already initialized with EntityTypeKey[TestProtocol]("envelope-shard")
      val ex = intercept[Exception] {
        sharding.init(
          Entity(EntityTypeKey[IdTestProtocol]("envelope-shard"), ctx => behaviorWithId(ctx.shard))
            .withStopMessage(IdStopPlz()))
      }

      ex.getMessage should include("already initialized")
    }

    "EntityRef - tell" in {
      val charlieRef = sharding.entityRefFor(typeKey, "charlie")

      val p = TestProbe[String]()

      charlieRef ! WhoAreYou(p.ref)
      p.receiveMessage() should startWith("I'm charlie")

      charlieRef.tell(WhoAreYou(p.ref))
      p.receiveMessage() should startWith("I'm charlie")

      charlieRef ! StopPlz()
    }

    "EntityRef - ask" in {
      val bobRef = sharding.entityRefFor(typeKey, "bob")
      val charlieRef = sharding.entityRefFor(typeKey, "charlie")

      val reply1 = bobRef ? WhoAreYou // TODO document that WhoAreYou(_) would not work
      reply1.futureValue should startWith("I'm bob")

      val reply2 = charlieRef.ask(WhoAreYou)
      reply2.futureValue should startWith("I'm charlie")

      bobRef ! StopPlz()
    }

    "EntityRef - ActorContext.ask" in {
      val aliceRef = sharding.entityRefFor(typeKey, "alice")

      val p = TestProbe[TheReply]()

      spawn(Behaviors.setup[TheReply] { ctx =>
        ctx.ask(aliceRef)(WhoAreYou) {
          case Success(name) => TheReply(name)
          case Failure(ex)   => TheReply(ex.getMessage)
        }

        Behaviors.receiveMessage[TheReply] { reply =>
          p.ref ! reply
          Behaviors.same
        }
      })

      p.receiveMessage().s should startWith("I'm alice")

      aliceRef ! StopPlz()

      // FIXME #26514: doesn't compile with Scala 2.13.0-M5
      /*
      // make sure request with multiple parameters compile
      Behaviors.setup[TheReply] { ctx =>
        ctx.ask(aliceRef)(WhoAreYou2(17, _)) {
          case Success(name) => TheReply(name)
          case Failure(ex)   => TheReply(ex.getMessage)
        }

        Behaviors.empty
      }
     */
    }

    "EntityRef - AskTimeoutException" in {
      val ignorantKey = EntityTypeKey[TestProtocol]("ignorant")

      sharding.init(Entity(ignorantKey, _ => Behaviors.ignore[TestProtocol]).withStopMessage(StopPlz()))

      val ref = sharding.entityRefFor(ignorantKey, "sloppy")

      val reply = ref.ask(WhoAreYou)(Timeout(10.millis))
      val exc = reply.failed.futureValue
      exc.getClass should ===(classOf[AskTimeoutException])
      exc.getMessage should startWith("Ask timed out on")
      exc.getMessage should include(ignorantKey.toString)
      exc.getMessage should include("sloppy") // the entity id
      exc.getMessage should include(ref.toString)
      exc.getMessage should include(s"[${classOf[WhoAreYou].getName}]") // message class
      exc.getMessage should include("[10 ms]") // timeout
    }

    "handle untyped StartEntity message" in {
      // it is normally using envolopes, but the untyped StartEntity message can be sent internally,
      // e.g. for remember entities

      val totalCountBefore = totalEntityCount1()

      val p = TestProbe[Any]()
      shardingRef1.toUntyped.tell(akka.cluster.sharding.ShardRegion.StartEntity("startEntity-1"), p.ref.toUntyped)
      p.expectMessageType[akka.cluster.sharding.ShardRegion.StartEntityAck]

      eventually {
        val totalCountAfter = totalEntityCount1()
        totalCountAfter should ===(totalCountBefore + 1)
      }
    }

    "handle typed StartEntity message" in {
      val totalCountBefore = totalEntityCount1()

      shardingRef1 ! StartEntity("startEntity-2")

      eventually {
        val totalCountAfter = totalEntityCount1()
        totalCountAfter should ===(totalCountBefore + 1)
      }
    }

    "use the stopMessage for leaving/rebalance" in {
      // use many entites to reduce the risk that all are hashed to the same shard/node
      val numberOfEntities = 100
      val probe1 = TestProbe[String]()
      (1 to numberOfEntities).foreach { n =>
        shardingRef1 ! ShardingEnvelope(s"test$n", WhoAreYou(probe1.ref))
      }
      val replies1 = probe1.receiveMessages(numberOfEntities, 10.seconds)

      Cluster(system2).manager ! Leave(Cluster(system2).selfMember.address)

      eventually {
        // if it wouldn't receive the StopPlz and stop the leaving would take longer and this would fail
        Cluster(system2).isTerminated should ===(true)
      }

      val probe2 = TestProbe[String]()
      (1 to numberOfEntities).foreach { n =>
        shardingRef1 ! ShardingEnvelope(s"test$n", WhoAreYou(probe2.ref))
      }
      val replies2 = probe2.receiveMessages(numberOfEntities, 10.seconds)
      replies2 should !==(replies1) // different addresses
    }
  }
}
