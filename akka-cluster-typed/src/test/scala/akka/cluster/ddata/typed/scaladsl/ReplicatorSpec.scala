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
import akka.testkit.typed.{ TestKit, TestKitSettings }
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

  val Key = GCounterKey("counter")

  def client(replicator: ActorRef[Replicator.Command])(implicit cluster: Cluster): Behavior[ClientCommand] =
    Behaviors.deferred[ClientCommand] { ctx ⇒
      replicator ! Replicator.Subscribe(Key, ctx.responseRef(classOf[Replicator.Changed[GCounter]]))

      def behavior(cachedValue: Int): Behavior[ClientCommand] = {
        Behaviors.immutable[ClientCommand] { (ctx, msg) ⇒
          msg match {
            case Increment ⇒
              val responseTo = ctx.responseRef(classOf[Replicator.UpdateResponse[GCounter]])
              replicator ! Replicator.Update(Key, GCounter.empty, Replicator.WriteLocal, responseTo)(_ + 1)
              Behaviors.same

            case GetValue(replyTo) ⇒
              val responseTo = ctx.responseRef(classOf[Replicator.GetResponse[GCounter]])
              replicator ! Replicator.Get(Key, Replicator.ReadLocal, responseTo, Some(replyTo))
              Behaviors.same

            case GetCachedValue(replyTo) ⇒
              val responseTo = ctx.responseRef(classOf[Replicator.GetResponse[GCounter]])
              replicator ! Replicator.Get(Key, Replicator.ReadLocal, responseTo, Some(replyTo))
              Behaviors.same

          }
        }
      }
        .onResponse[Replicator.GetResponse[GCounter]] { (_, msg) ⇒
          msg match {
            case rsp @ Replicator.GetSuccess(Key, Some(replyTo: ActorRef[Int] @unchecked)) ⇒
              val value = rsp.get(Key).value.toInt
              replyTo ! value
              Behaviors.same
            case _ ⇒
              Behaviors.unhandled // not dealing with failures
          }
        }
        .onResponse[Replicator.Changed[GCounter]] { (ctx, chg) ⇒
          val value = chg.get(Key).value.intValue
          behavior(value)
        }
        .onResponse[Replicator.UpdateResponse[GCounter]] { (_, _) ⇒
          Behaviors.same // ok
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

class ReplicatorSpec extends TestKit(ReplicatorSpec.config) with TypedAkkaSpecWithShutdown with Eventually {

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
      probe.expectMsg(1)
    }

    "have API for Subscribe" in {
      val replicator = spawn(Replicator.behavior(settings))
      val c = spawn(client(replicator))

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
      val c = spawn(client(replicator))

      val probe = TestProbe[Int]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMsg(1)
    }
  }
}

