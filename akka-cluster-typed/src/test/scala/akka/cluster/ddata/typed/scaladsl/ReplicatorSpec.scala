/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

import scala.util.Failure
import scala.util.Success

import org.scalatest.WordSpecLike
import akka.actor.testkit.typed.TestKitSettings
import akka.cluster.ddata.SelfUniqueAddress

// #sample
import akka.actor.typed.Scheduler
import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.cluster.ddata.{ GCounter, GCounterKey }
import akka.actor.testkit.typed.scaladsl._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration._

// #sample

object ReplicatorSpec {

  val config = ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.remote.classic.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.remote.artery.canonical.hostname = 127.0.0.1
    """)

  // #sample
  sealed trait ClientCommand
  final case object Increment extends ClientCommand
  final case class GetValue(replyTo: ActorRef[Int]) extends ClientCommand
  final case class GetCachedValue(replyTo: ActorRef[Int]) extends ClientCommand
  private sealed trait InternalMsg extends ClientCommand
  private case class InternalUpdateResponse(rsp: Replicator.UpdateResponse[GCounter]) extends InternalMsg
  private case class InternalGetResponse(rsp: Replicator.GetResponse[GCounter], replyTo: ActorRef[Int])
      extends InternalMsg
  private case class InternalChanged(chg: Replicator.Changed[GCounter]) extends InternalMsg

  val Key = GCounterKey("counter")

  def client(replicator: ActorRef[Replicator.Command])(implicit node: SelfUniqueAddress): Behavior[ClientCommand] =
    Behaviors.setup[ClientCommand] { ctx =>
      val updateResponseAdapter: ActorRef[Replicator.UpdateResponse[GCounter]] =
        ctx.messageAdapter(InternalUpdateResponse.apply)

      val changedAdapter: ActorRef[Replicator.Changed[GCounter]] =
        ctx.messageAdapter(InternalChanged.apply)

      replicator ! Replicator.Subscribe(Key, changedAdapter)

      implicit val askTimeout: Timeout = Timeout(10.seconds)

      def behavior(cachedValue: Int): Behavior[ClientCommand] = {
        Behaviors.receiveMessage[ClientCommand] {
          case Increment =>
            replicator ! Replicator.Update(Key, GCounter.empty, Replicator.WriteLocal, updateResponseAdapter)(_ :+ 1)
            Behaviors.same

          case GetValue(replyTo) =>
            ctx.ask[Replicator.Get[GCounter], Replicator.GetResponse[GCounter]](replicator)(askReplyTo =>
              Replicator.Get(Key, Replicator.ReadLocal, askReplyTo)) {
              case Success(value) => InternalGetResponse(value, replyTo)
              case Failure(ex)    => throw ex // unexpected ask timeout
            }

            Behaviors.same

          case GetCachedValue(replyTo) =>
            replyTo ! cachedValue
            Behaviors.same

          case internal: InternalMsg =>
            internal match {
              case InternalUpdateResponse(_) => Behaviors.same // ok

              case InternalGetResponse(rsp @ Replicator.GetSuccess(Key), replyTo) =>
                val value = rsp.get(Key).value.toInt
                replyTo ! value
                Behaviors.same

              case InternalGetResponse(_, _) =>
                Behaviors.unhandled // not dealing with failures

              case InternalChanged(chg @ Replicator.Changed(Key)) =>
                val value = chg.get(Key).value.intValue
                behavior(value)
            }
        }
      }

      behavior(cachedValue = 0)
    }
  // #sample

  object CompileOnlyTest {
    def shouldHaveConvenienceForAsk(): Unit = {
      val replicator: ActorRef[Replicator.Command] = ???
      implicit val timeout = Timeout(3.seconds)
      implicit val scheduler: Scheduler = ???
      implicit val cluster: SelfUniqueAddress = ???

      val reply1: Future[GetResponse[GCounter]] = replicator.ask(Replicator.Get(Key, Replicator.ReadLocal))

      val reply2: Future[UpdateResponse[GCounter]] =
        replicator.ask(Replicator.Update(Key, GCounter.empty, Replicator.WriteLocal)(_ :+ 1))

      val reply3: Future[DeleteResponse[GCounter]] = replicator.ask(Replicator.Delete(Key, Replicator.WriteLocal))

      val reply4: Future[ReplicaCount] = replicator.ask(Replicator.GetReplicaCount())

      // suppress unused compiler warnings
      println("" + reply1 + reply2 + reply3 + reply4)
    }

    def shouldHaveUnapplyForResponseTypes(): Unit = {
      val getResponse: GetResponse[GCounter] = ???
      getResponse match {
        case GetSuccess(Key) =>
        case GetFailure(Key) =>
        case NotFound(Key)   =>
      }

      val updateResponse: UpdateResponse[GCounter] = ???
      updateResponse match {
        case UpdateSuccess(Key)       =>
        case ModifyFailure(Key, _, _) =>
        case UpdateTimeout(Key)       =>
        case StoreFailure(Key)        =>
        case UpdateFailure(Key)       =>
      }

      val deleteResponse: DeleteResponse[GCounter] = ???
      deleteResponse match {
        case DeleteSuccess(Key)            =>
        case ReplicationDeleteFailure(Key) =>
        case DataDeleted(Key)              =>
      }

      val replicaCount: ReplicaCount = ???
      replicaCount match {
        case ReplicaCount(_) =>
      }
    }
  }

}

class ReplicatorSpec extends ScalaTestWithActorTestKit(ReplicatorSpec.config) with WordSpecLike {

  import ReplicatorSpec._

  implicit val testSettings = TestKitSettings(system)
  val settings = ReplicatorSettings(system)
  implicit val selfNodeAddress = DistributedData(system).selfUniqueAddress

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

    "have the prefixed replicator name" in {
      ReplicatorSettings.name(system) should ===("typedDdataReplicator")
    }
  }
}
