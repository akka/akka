/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata.typed.scaladsl

import akka.actor.Scheduler
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, StartSupport, TypedSpec }
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl._
import akka.cluster.Cluster
import akka.cluster.ddata.{ GCounter, GCounterKey, ReplicatedData }
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.duration._

object ReplicatorSpec {

  val config = ConfigFactory.parseString(
    """
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.remote.artery.canonical.hostname = 127.0.0.1
    """)

  sealed trait ClientCommand
  final case object Increment extends ClientCommand
  final case class GetValue(replyTo: ActorRef[Int]) extends ClientCommand
  final case class GetCachedValue(replyTo: ActorRef[Int]) extends ClientCommand
  private sealed trait InternalMsg extends ClientCommand
  private case class InternalUpdateResponse[A <: ReplicatedData](rsp: Replicator.UpdateResponse[A]) extends InternalMsg
  private case class InternalGetResponse[A <: ReplicatedData](rsp: Replicator.GetResponse[A]) extends InternalMsg
  private case class InternalChanged[A <: ReplicatedData](chg: Replicator.Changed[A]) extends InternalMsg

  val Key = GCounterKey("counter")

  def client(replicator: ActorRef[Replicator.Command])(implicit cluster: Cluster): Behavior[ClientCommand] =
    Actor.deferred[ClientCommand] { ctx ⇒
      val updateResponseAdapter: ActorRef[Replicator.UpdateResponse[GCounter]] =
        ctx.spawnAdapter(InternalUpdateResponse.apply)

      val getResponseAdapter: ActorRef[Replicator.GetResponse[GCounter]] =
        ctx.spawnAdapter(InternalGetResponse.apply)

      val changedAdapter: ActorRef[Replicator.Changed[GCounter]] =
        ctx.spawnAdapter(InternalChanged.apply)

      replicator ! Replicator.Subscribe(Key, changedAdapter)

      def behavior(cachedValue: Int): Behavior[ClientCommand] = {
        Actor.immutable[ClientCommand] { (ctx, msg) ⇒
          msg match {
            case Increment ⇒
              replicator ! Replicator.Update(Key, GCounter.empty, Replicator.WriteLocal, updateResponseAdapter)(_ + 1)
              Actor.same

            case GetValue(replyTo) ⇒
              replicator ! Replicator.Get(Key, Replicator.ReadLocal, getResponseAdapter, Some(replyTo))
              Actor.same

            case GetCachedValue(replyTo) ⇒
              replicator ! Replicator.Get(Key, Replicator.ReadLocal, getResponseAdapter, Some(replyTo))
              Actor.same

            case internal: InternalMsg ⇒ internal match {
              case InternalUpdateResponse(_) ⇒ Actor.same // ok

              case InternalGetResponse(rsp @ Replicator.GetSuccess(Key, Some(replyTo: ActorRef[Int] @unchecked))) ⇒
                val value = rsp.get(Key).value.toInt
                replyTo ! value
                Actor.same

              case InternalGetResponse(rsp) ⇒
                Actor.unhandled // not dealing with failures

              case InternalChanged(chg @ Replicator.Changed(Key)) ⇒
                val value = chg.get(Key).value.intValue
                behavior(value)
            }
          }
        }
      }

      behavior(cachedValue = 0)
    }

  object CompileOnlyTest {
    def shouldHaveConvenienceForAsk(): Unit = {
      val replicator: ActorRef[Replicator.Command] = ???
      implicit val timeout = Timeout(3.seconds)
      implicit val scheduler: Scheduler = ???
      implicit val cluster: Cluster = ???

      val reply1: Future[GetResponse[GCounter]] = replicator ? Replicator.Get(Key, Replicator.ReadLocal)

      val reply2: Future[UpdateResponse[GCounter]] =
        replicator ? Replicator.Update(Key, GCounter.empty, Replicator.WriteLocal)(_ + 1)

      val reply3: Future[DeleteResponse[GCounter]] = replicator ? Replicator.Delete(Key, Replicator.WriteLocal)

      val reply4: Future[ReplicaCount] = replicator ? Replicator.GetReplicaCount()

      // supress unused compiler warnings
      println("" + reply1 + reply2 + reply3 + reply4)
    }
  }

}

class ReplicatorSpec extends TypedSpec(ReplicatorSpec.config) with Eventually with StartSupport {

  import ReplicatorSpec._

  implicit val testSettings = TestKitSettings(system)
  val settings = ReplicatorSettings(system)
  implicit val cluster = Cluster(system.toUntyped)

  "Replicator" must {

    "have API for Update and Get" in {
      val replicator = start(Replicator.behavior(settings))
      val c = start(client(replicator))

      val probe = TestProbe[Int]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMsg(1)
    }

    "have API for Subscribe" in {
      val replicator = start(Replicator.behavior(settings))
      val c = start(client(replicator))

      val probe = TestProbe[Int]
      c ! Increment
      c ! Increment
      eventually {
        c ! GetCachedValue(probe.ref)
        probe.expectMsg(2)
      }
      c ! Increment
      eventually {
        c ! GetCachedValue(probe.ref)
        probe.expectMsg(3)
      }
    }

    "have an extension" in {
      val replicator = DistributedData(system).replicator
      val c = start(client(replicator))

      val probe = TestProbe[Int]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMsg(1)
    }
  }
}

