/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

import akka.actor.Scheduler
import akka.actor.typed.{ ActorRef, Behavior, TypedAkkaSpecWithShutdown }
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.Cluster
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.cluster.ddata.{ GCounter, GCounterKey, ReplicatedData }
import akka.testkit.typed.scaladsl._
import akka.testkit.typed.TestKitSettings
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.duration._

object ReplicatorSpec {

  val config = ConfigFactory.parseString(
    """
    akka.loglevel = DEBUG
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
    Behaviors.setup[ClientCommand] { ctx ⇒

      val updateResponseAdapter: ActorRef[Replicator.UpdateResponse[GCounter]] =
        ctx.messageAdapter(InternalUpdateResponse.apply)

      val getResponseAdapter: ActorRef[Replicator.GetResponse[GCounter]] =
        ctx.messageAdapter(InternalGetResponse.apply)

      val changedAdapter: ActorRef[Replicator.Changed[GCounter]] =
        ctx.messageAdapter(InternalChanged.apply)

      replicator ! Replicator.Subscribe(Key, changedAdapter)

      def behavior(cachedValue: Int): Behavior[ClientCommand] = {
        Behaviors.receive[ClientCommand] { (ctx, msg) ⇒
          msg match {
            case Increment ⇒
              replicator ! Replicator.Update(Key, GCounter.empty, Replicator.WriteLocal, updateResponseAdapter)(_ + 1)
              Behaviors.same

            case GetValue(replyTo) ⇒
              replicator ! Replicator.Get(Key, Replicator.ReadLocal, getResponseAdapter, Some(replyTo))
              Behaviors.same

            case GetCachedValue(replyTo) ⇒
              replicator ! Replicator.Get(Key, Replicator.ReadLocal, getResponseAdapter, Some(replyTo))
              Behaviors.same

            case internal: InternalMsg ⇒ internal match {
              case InternalUpdateResponse(_) ⇒ Behaviors.same // ok

              case InternalGetResponse(rsp @ Replicator.GetSuccess(Key, Some(replyTo: ActorRef[Int] @unchecked))) ⇒
                val value = rsp.get(Key).value.toInt
                replyTo ! value
                Behaviors.same

              case InternalGetResponse(rsp) ⇒
                Behaviors.unhandled // not dealing with failures

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

      // suppress unused compiler warnings
      println("" + reply1 + reply2 + reply3 + reply4)
    }
  }

}

class ReplicatorSpec extends ActorTestKit with TypedAkkaSpecWithShutdown with Eventually {

  override def config = ReplicatorSpec.config

  import ReplicatorSpec._

  implicit val testSettings = TestKitSettings(system)
  val settings = ReplicatorSettings(system)
  implicit val cluster = Cluster(system.toUntyped)

  "Replicator" must {

    "have API for Update and Get" in {
      val replicator = spawn(Replicator.behavior(settings))
      val c = spawn(client(replicator))

      val probe = TestProbe[Int]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(1)
    }

    "have API for Subscribe" in {
      val replicator = spawn(Replicator.behavior(settings))
      val c = spawn(client(replicator))

      val probe = TestProbe[Int]
      c ! Increment
      c ! Increment
      eventually {
        c ! GetCachedValue(probe.ref)
        probe.expectMessage(2)
      }
      c ! Increment
      eventually {
        c ! GetCachedValue(probe.ref)
        probe.expectMessage(3)
      }
    }

    "have an extension" in {
      val replicator = DistributedData(system).replicator
      val c = spawn(client(replicator))

      val probe = TestProbe[Int]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(1)
    }
  }
}

