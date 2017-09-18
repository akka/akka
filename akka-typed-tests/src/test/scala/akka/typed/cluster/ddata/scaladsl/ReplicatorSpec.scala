/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.ddata.scaladsl

import scala.concurrent.duration._

import akka.cluster.Cluster

import akka.cluster.ddata.GCounter
import akka.cluster.ddata.GCounterKey
import akka.cluster.ddata.ReplicatedData
import akka.typed.ActorRef
import akka.typed.ActorSystem
import akka.typed.Behavior
import akka.typed.TypedSpec
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.AskPattern._
import akka.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl._
import com.typesafe.config.ConfigFactory

object ReplicatorSpec {

  val config = ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    """)

  sealed trait ClientCommand
  final case object Increment extends ClientCommand
  final case class GetValue(replyTo: ActorRef[Int]) extends ClientCommand
  private sealed trait InternalMsg extends ClientCommand
  private case class InternalUpdateResponse[A <: ReplicatedData](rsp: Replicator.UpdateResponse[A]) extends InternalMsg
  private case class InternalGetResponse[A <: ReplicatedData](rsp: Replicator.GetResponse[A]) extends InternalMsg

  val Key = GCounterKey("counter")

  def client(replicator: ActorRef[Replicator.Command[_]])(implicit cluster: Cluster): Behavior[ClientCommand] =
    Actor.deferred[ClientCommand] { ctx ⇒
      val updateResponseAdapter: ActorRef[Replicator.UpdateResponse[GCounter]] =
        ctx.spawnAdapter(InternalUpdateResponse.apply)

      val getResponseAdapter: ActorRef[Replicator.GetResponse[GCounter]] =
        ctx.spawnAdapter(InternalGetResponse.apply)

      Actor.immutable[ClientCommand] { (ctx, msg) ⇒
        msg match {
          case Increment ⇒
            replicator ! Replicator.Update(Key, GCounter.empty, Replicator.WriteLocal)(_ + 1)(updateResponseAdapter)
            Actor.same

          case GetValue(replyTo) ⇒
            replicator ! Replicator.Get(Key, Replicator.ReadLocal, Some(replyTo))(getResponseAdapter)
            Actor.same

          case internal: InternalMsg ⇒ internal match {
            case InternalUpdateResponse(_) ⇒ Actor.same // ok

            case InternalGetResponse(rsp @ Replicator.GetSuccess(Key, Some(replyTo: ActorRef[Int] @unchecked))) ⇒
              val value = rsp.get(Key).value.toInt
              replyTo ! value
              Actor.same

            case InternalGetResponse(rsp) ⇒
              Actor.unhandled // not dealing with failures
          }
        }
      }
    }
}

class ReplicatorSpec extends TypedSpec(ReplicatorSpec.config) {
  import ReplicatorSpec._

  trait RealTests extends StartSupport {
    implicit def system: ActorSystem[TypedSpec.Command]
    implicit val testSettings = TestKitSettings(system)
    val settings = ReplicatorSettings(system)
    implicit val cluster = Cluster(system.toUntyped)

    def `API prototype`(): Unit = {

      val replicator = start(Replicator.behavior(settings))

      val c = start(client(replicator))

      val probe = TestProbe[Int]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMsg(1)
    }

  }

  object `A ReplicatorBehavior (real, adapted)` extends RealTests with AdaptedSystem
}
