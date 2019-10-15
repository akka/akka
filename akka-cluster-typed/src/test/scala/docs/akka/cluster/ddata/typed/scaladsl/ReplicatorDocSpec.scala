/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.ddata.typed.scaladsl

import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator
import org.scalatest.WordSpecLike

import akka.actor.testkit.typed.scaladsl._
import com.typesafe.config.ConfigFactory

// #sample
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.ddata.GCounter
import akka.cluster.ddata.GCounterKey
import akka.cluster.ddata.typed.scaladsl.Replicator._

// #sample

object ReplicatorDocSpec {

  val config = ConfigFactory.parseString("""
    akka.actor.provider = cluster
    akka.remote.classic.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.remote.artery.canonical.hostname = 127.0.0.1
    """)

  // #sample
  object Counter {
    sealed trait Command
    final case object Increment extends Command
    final case class GetValue(replyTo: ActorRef[Int]) extends Command
    final case class GetCachedValue(replyTo: ActorRef[Int]) extends Command
    private sealed trait InternalCommand extends Command
    private case class InternalUpdateResponse(rsp: Replicator.UpdateResponse[GCounter]) extends InternalCommand
    private case class InternalGetResponse(rsp: Replicator.GetResponse[GCounter], replyTo: ActorRef[Int])
        extends InternalCommand
    private case class InternalSubscribeResponse(chg: Replicator.SubscribeResponse[GCounter]) extends InternalCommand

    def apply(key: GCounterKey): Behavior[Command] =
      Behaviors.setup[Command] { context =>
        //#selfUniqueAddress
        implicit val node: SelfUniqueAddress = DistributedData(context.system).selfUniqueAddress
        //#selfUniqueAddress

        // adapter that turns the response messages from the replicator into our own protocol
        DistributedData.withReplicatorMessageAdapter[Command, GCounter] { replicatorAdapter =>
          //#subscribe
          // Subscribe to changes of the given `key`.
          replicatorAdapter.subscribe(key, InternalSubscribeResponse.apply)
          //#subscribe

          def updated(cachedValue: Int): Behavior[Command] = {
            Behaviors.receiveMessage[Command] {
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

              case internal: InternalCommand =>
                internal match {
                  case InternalUpdateResponse(_) => Behaviors.same // ok

                  case InternalGetResponse(rsp @ Replicator.GetSuccess(`key`), replyTo) =>
                    val value = rsp.get(key).value.toInt
                    replyTo ! value
                    Behaviors.same

                  case InternalGetResponse(_, _) =>
                    Behaviors.unhandled // not dealing with failures
                  case InternalSubscribeResponse(chg @ Replicator.Changed(`key`)) =>
                    val value = chg.get(key).value.intValue
                    updated(value)

                  case InternalSubscribeResponse(Replicator.Deleted(_)) =>
                    Behaviors.unhandled // no deletes
                }
            }
          }

          updated(cachedValue = 0)
        }
      }
  }
  // #sample

}

class ReplicatorDocSpec
    extends ScalaTestWithActorTestKit(ReplicatorDocSpec.config)
    with WordSpecLike
    with LogCapturing {

  import ReplicatorDocSpec._

  implicit val selfNodeAddress = DistributedData(system).selfUniqueAddress

  "Replicator" must {

    "have API for Update and Get" in {
      val c = spawn(Counter(GCounterKey("counter1")))

      val probe = createTestProbe[Int]()
      c ! Counter.Increment
      c ! Counter.GetValue(probe.ref)
      probe.expectMessage(1)
    }

    "have API for Subscribe" in {
      val c = spawn(Counter(GCounterKey("counter2")))

      val probe = createTestProbe[Int]()
      c ! Counter.Increment
      c ! Counter.Increment
      eventually {
        c ! Counter.GetCachedValue(probe.ref)
        probe.expectMessage(2)
      }
      c ! Counter.Increment
      eventually {
        c ! Counter.GetCachedValue(probe.ref)
        probe.expectMessage(3)
      }
    }

    "have an extension" in {
      val key = GCounterKey("counter3")
      val c = spawn(Counter(key))

      val probe = createTestProbe[Int]()
      c ! Counter.Increment
      c ! Counter.GetValue(probe.ref)
      probe.expectMessage(1)

      val getReplyProbe = createTestProbe[Replicator.GetResponse[GCounter]]()
      val replicator = DistributedData(system).replicator
      replicator ! Replicator.Get(key, Replicator.ReadLocal, getReplyProbe.ref)
      val rsp = getReplyProbe.expectMessageType[GetSuccess[GCounter]]
      rsp.get(key).value.toInt should ===(1)
    }

  }
}
