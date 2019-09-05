/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

// FIXME move to doc package

import org.scalatest.WordSpecLike
import akka.cluster.ddata.SelfUniqueAddress

// #sample
import akka.actor.typed.Scheduler
import akka.actor.typed.{ ActorRef, Behavior }
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

  def client(key: GCounterKey): Behavior[ClientCommand] =
    Behaviors.setup[ClientCommand] { ctx =>
      implicit val node: SelfUniqueAddress = DistributedData(ctx.system).selfUniqueAddress

      // adapter that turns the response messages from the replicator into our own protocol
      DistributedData.withReplicatorMessageAdapter[ClientCommand, GCounter] { replicatorAdapter =>
        replicatorAdapter.subscribe(key, InternalChanged.apply)

        def behavior(cachedValue: Int): Behavior[ClientCommand] = {
          Behaviors.receiveMessage[ClientCommand] {
            case Increment =>
              replicatorAdapter.askUpdate(
                askReplyTo => Replicator.Update(key, GCounter.empty, Replicator.WriteLocal, askReplyTo)(_ :+ 1),
                InternalUpdateResponse.apply)

              Behaviors.same

            case GetValue(replyTo) =>
              replicatorAdapter.askGet(
                askReplyTo => Replicator.Get(key, Replicator.ReadLocal, askReplyTo),
                value => InternalGetResponse(value, replyTo))

              Behaviors.same

            case GetCachedValue(replyTo) =>
              replyTo ! cachedValue
              Behaviors.same

            case internal: InternalMsg =>
              internal match {
                case InternalUpdateResponse(_) => Behaviors.same // ok

                case InternalGetResponse(rsp @ Replicator.GetSuccess(`key`), replyTo) =>
                  val value = rsp.get(key).value.toInt
                  replyTo ! value
                  Behaviors.same

                case InternalGetResponse(_, _) =>
                  Behaviors.unhandled // not dealing with failures

                case InternalChanged(chg @ Replicator.Changed(`key`)) =>
                  val value = chg.get(key).value.intValue
                  behavior(value)
              }
          }
        }

        behavior(cachedValue = 0)
      }
    }
  // #sample

  object CompileOnlyTest {
    def shouldHaveConvenienceForAsk(): Unit = {
      import akka.actor.typed.scaladsl.AskPattern._

      val replicator: ActorRef[Replicator.Command] = ???
      implicit val timeout = Timeout(3.seconds)
      implicit val scheduler: Scheduler = ???
      implicit val cluster: SelfUniqueAddress = ???
      val key = GCounterKey("counter")

      val reply1: Future[GetResponse[GCounter]] = replicator.ask(Replicator.Get(key, Replicator.ReadLocal))

      val reply2: Future[UpdateResponse[GCounter]] =
        replicator.ask(Replicator.Update(key, GCounter.empty, Replicator.WriteLocal)(_ :+ 1))

      val reply3: Future[DeleteResponse[GCounter]] = replicator.ask(Replicator.Delete(key, Replicator.WriteLocal))

      val reply4: Future[ReplicaCount] = replicator.ask(Replicator.GetReplicaCount())

      // suppress unused compiler warnings
      println("" + reply1 + reply2 + reply3 + reply4)
    }

    def shouldHaveConvenienceForAsk2(): Unit = {
      implicit val cluster: SelfUniqueAddress = ???
      val replicatorAdapter: ReplicatorMessageAdapter[ClientCommand, GCounter] = ???
      val replyTo: ActorRef[Int] = ???
      val key = GCounterKey("counter")

      //#curried-update
      // alternative way to define the `createRequest` function
      // Replicator.Update instance has a curried `apply` method
      replicatorAdapter.askUpdate(
        Replicator.Update(key, GCounter.empty, Replicator.WriteLocal)(_ :+ 1),
        InternalUpdateResponse.apply)

      // that is the same as
      replicatorAdapter.askUpdate(
        askReplyTo => Replicator.Update(key, GCounter.empty, Replicator.WriteLocal, askReplyTo)(_ :+ 1),
        InternalUpdateResponse.apply)
      //#curried-update

      //#curried-get
      // alternative way to define the `createRequest` function
      // Replicator.Get instance has a curried `apply` method
      replicatorAdapter.askGet(Replicator.Get(key, Replicator.ReadLocal), value => InternalGetResponse(value, replyTo))

      // that is the same as
      replicatorAdapter.askGet(
        askReplyTo => Replicator.Get(key, Replicator.ReadLocal, askReplyTo),
        value => InternalGetResponse(value, replyTo))
      //#curried-get
    }

    def shouldHaveUnapplyForResponseTypes(): Unit = {
      val getResponse: GetResponse[GCounter] = ???
      val key = GCounterKey("counter")

      getResponse match {
        case GetSuccess(`key`) =>
        case GetFailure(`key`) =>
        case NotFound(`key`)   =>
      }

      val updateResponse: UpdateResponse[GCounter] = ???
      updateResponse match {
        case UpdateSuccess(`key`)       =>
        case ModifyFailure(`key`, _, _) =>
        case UpdateTimeout(`key`)       =>
        case StoreFailure(`key`)        =>
        case UpdateFailure(`key`)       =>
      }

      val deleteResponse: DeleteResponse[GCounter] = ???
      deleteResponse match {
        case DeleteSuccess(`key`)            =>
        case ReplicationDeleteFailure(`key`) =>
        case DataDeleted(`key`)              =>
      }

      val replicaCount: ReplicaCount = ???
      replicaCount match {
        case ReplicaCount(_) =>
      }
    }
  }

}

class ReplicatorSpec extends ScalaTestWithActorTestKit(ReplicatorSpec.config) with WordSpecLike with LogCapturing {

  import ReplicatorSpec._

  implicit val selfNodeAddress = DistributedData(system).selfUniqueAddress

  "Replicator" must {

    "have API for Update and Get" in {
      val c = spawn(client(GCounterKey("counter1")))

      val probe = createTestProbe[Int]()
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(1)
    }

    "have API for Subscribe" in {
      val c = spawn(client(GCounterKey("counter2")))

      val probe = createTestProbe[Int]()
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
      val key = GCounterKey("counter3")
      val c = spawn(client(key))

      val probe = createTestProbe[Int]()
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(1)

      val getReplyProbe = createTestProbe[Replicator.GetResponse[GCounter]]()
      val replicator = DistributedData(system).replicator
      replicator ! Replicator.Get(key, Replicator.ReadLocal, getReplyProbe.ref)
      val rsp = getReplyProbe.expectMessageType[GetSuccess[GCounter]]
      rsp.get(key).value.toInt should ===(1)
    }

    "have the prefixed replicator name" in {
      ReplicatorSettings.name(system) should ===("typedDdataReplicator")
    }
  }
}
