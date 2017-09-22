/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.typed.cluster.sharding

import akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy
import akka.typed.{ ActorRef, ActorSystem, Props, TypedSpec }
import akka.typed.cluster.Cluster
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

object ClusterShardingSpec {
  val config = ConfigFactory.parseString(
    """
      akka.actor.provider = cluster
      
      // akka.loglevel = debug
      
      akka.remote.artery.enabled = true
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      
      akka.cluster.jmx.multi-mbeans-in-same-jvm = on
      
      akka.coordinated-shutdown.terminate-actor-system = off
      
      akka.actor {
        serialize-messages = off
        allow-java-serialization = off
      }
    """.stripMargin
  )

  sealed trait TestProtocol
  final case class ReplyPlz(toMe: ActorRef[String]) extends TestProtocol
  final case class WhoAreYou(replyTo: ActorRef[String]) extends TestProtocol
  final case class StopPlz() extends TestProtocol

  sealed trait IdTestProtocol { def id: String }
  final case class IdReplyPlz(id: String, toMe: ActorRef[String]) extends IdTestProtocol
  final case class IdWhoAreYou(id: String, replyTo: ActorRef[String]) extends IdTestProtocol
  final case class IdStopPlz(id: String) extends IdTestProtocol

}

class ClusterShardingSpec extends TypedSpec(ClusterShardingSpec.config) with ScalaFutures {
  import ClusterShardingSpec._

  implicit val system = adaptedSystem
  implicit val testkitSettings = TestKitSettings(system)
  val sharding = ClusterSharding(adaptedSystem)

  implicit val untypedSystem = ActorSystemAdapter.toUntyped(adaptedSystem)
  private val untypedCluster = akka.cluster.Cluster(untypedSystem)

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

  object `Typed cluster sharding` {

    untypedCluster.join(untypedCluster.selfAddress)

    def `01 must send messsages via cluster sharding, using envelopes`(): Unit = {
      val ref = sharding.spawn(
        behavior,
        Props.empty,
        "example",
        ClusterShardingSettings(adaptedSystem),
        10,
        StopPlz()
      )

      val p = TestProbe[String]()
      ref ! ShardingEnvelope("test", ReplyPlz(p.ref))
      p.expectMsg(10.seconds, "Hello!")

      ref ! ShardingEnvelope("test", StopPlz())
    }
    def `02 must send messsages via cluster sharding, without envelopes`(): Unit = {
      val ref = sharding.spawn(
        behaviorWithId,
        Props.empty,
        "example-02",
        ClusterShardingSettings(adaptedSystem),
        ShardingMessageExtractor.noEnvelope[IdTestProtocol](10, _.id),
        IdStopPlz("THE_ID_HERE")
      )

      val p = TestProbe[String]()
      ref ! IdReplyPlz("test", p.ref)
      p.expectMsg(10.seconds, "Hello!")

      ref ! IdStopPlz("test")
    }

    //    def `03 fail if starting sharding for already used typeName, but with wrong type`(): Unit = {
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
  }

  object `ShardedEntityRef` {

    untypedCluster.join(untypedCluster.selfAddress)

    //    def `02 must expose ShardedEntityRef`(): Unit = pendingUntilFixed {
    //      val charlieRef: ShardedEntityRef[TestProtocol] = sharding.entityRefFor[TestProtocol]("example", "charlie")
    //
    //      val p = TestProbe[String]()
    //
    //      charlieRef ! WhoAreYou(p.ref)
    //      p.expectMsg(10.seconds, "I'm charlie")
    //
    //      charlieRef ! StopPlz()
    //    }

  }

}
