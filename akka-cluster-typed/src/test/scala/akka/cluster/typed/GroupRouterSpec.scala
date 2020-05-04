/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import scala.concurrent.Promise

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.{ Behaviors, GroupRouter, Routers }
import akka.serialization.jackson.CborSerializable
import akka.util.Timeout

object GroupRouterSpec {
  def config = ConfigFactory.parseString(s"""
    akka {
      loglevel = debug
      actor.provider = cluster
      remote.classic.netty.tcp.port = 0
      remote.classic.netty.tcp.host = 127.0.0.1
      remote.artery {
        canonical {
          hostname = 127.0.0.1
          port = 0
        }
      }
    }
  """)
  def createSystem[T](behavior: Behavior[T], name: String) = ActorSystem(behavior, name, config)

  case object Ping extends CborSerializable

  trait Command
  case class UpdateWorker(actorRef: ActorRef[Ping.type]) extends Command
  case class GetWorkers(replyTo: ActorRef[Seq[ActorRef[Ping.type]]]) extends Command

  // receive Ping and send self to statsActorRef, so the statsActorRef will know who handle the message
  def pingPong(statsActorRef: ActorRef[Command]) = Behaviors.receive[Ping.type] { (ctx, msg) =>
    msg match {
      case Ping =>
        statsActorRef ! UpdateWorker(ctx.self)
        Behaviors.same
    }
  }

  // stats the actors who handle the messages and gives the result if someone ask
  def workerStatsBehavior(list: List[ActorRef[Ping.type]]): Behavior[Command] = Behaviors.receiveMessage {
    case UpdateWorker(actorRef) =>
      workerStatsBehavior(actorRef :: list)
    case GetWorkers(replyTo) =>
      replyTo ! list
      Behaviors.same
  }

  case class GroupRouterSpecSettings(node1WorkerCount: Int, node2WorkerCount: Int, messageCount: Int)

  val pingPongKey = ServiceKey[Ping.type]("ping-pong")

}

class GroupRouterSpec extends ScalaTestWithActorTestKit(GroupRouterSpec.config) with AnyWordSpecLike with LogCapturing {
  import GroupRouterSpec._

  def checkGroupRouterBehavior[T](groupRouter: GroupRouter[Ping.type], settings: GroupRouterSpecSettings)(
      resultCheck: (Seq[ActorRef[Ping.type]], Seq[ActorRef[Ping.type]]) => T): T = {
    import scala.concurrent.duration._

    import akka.actor.typed.scaladsl.AskPattern._
    implicit val system1 =
      createSystem(
        Behaviors.setup[Command] { ctx =>
          (0 until settings.node1WorkerCount).foreach { i =>
            val worker = ctx.spawn(pingPong(ctx.self), s"ping-pong-$i")
            ctx.system.receptionist ! Receptionist.Register(pingPongKey, worker)
          }
          val router = ctx.spawn(groupRouter, "group-router")

          // ensure all nodes are joined and all actors are discovered.
          ctx.system.receptionist ! Receptionist.Subscribe(
            pingPongKey,
            ctx.spawn(Behaviors.receiveMessage[Receptionist.Listing] {
              case pingPongKey.Listing(update)
                  if update.size == settings.node1WorkerCount + settings.node2WorkerCount =>
                (0 until settings.messageCount).foreach(_ => router ! Ping)
                Behaviors.empty
              case _ =>
                Behaviors.same
            }, "waiting-actor-discovery"))
          workerStatsBehavior(List.empty)
        },
        system.name)

    val system2 = createSystem(Behaviors.setup[Command] { ctx =>
      (0 until settings.node2WorkerCount).foreach { i =>
        val worker = ctx.spawn(pingPong(ctx.self), s"ping-pong-$i")
        ctx.system.receptionist ! Receptionist.Register(pingPongKey, worker)
      }
      workerStatsBehavior(List.empty)
    }, system.name)
    val node1 = Cluster(system1)
    node1.manager ! Join(node1.selfMember.address)
    val node2 = Cluster(system2)
    node2.manager ! Join(node1.selfMember.address)

    val statsPromise = Promise[(Seq[ActorRef[Ping.type]], Seq[ActorRef[Ping.type]])]()
    val cancelable = system.scheduler.scheduleAtFixedRate(200.millis, 200.millis)(() => {
      implicit val timeout = Timeout(3.seconds)
      val actorRefsInNode1 = system1.ask[Seq[ActorRef[Ping.type]]](ref => GetWorkers(ref)).futureValue
      val actorRefsInNode2 = system2.ask[Seq[ActorRef[Ping.type]]](ref => GetWorkers(ref)).futureValue
      // waiting all messages are handled
      if (actorRefsInNode1.size + actorRefsInNode2.size == settings.messageCount && !statsPromise.isCompleted) {
        statsPromise.success((actorRefsInNode1, actorRefsInNode2))
      }
    })(system.executionContext)

    try {
      val stats = statsPromise.future.futureValue
      cancelable.cancel()
      resultCheck(stats._1, stats._2)
    } finally {
      system1.terminate()
      system2.terminate()
    }
  }
  "GroupRouter" must {
    "use all reachable routees if preferLocalRoutees is not enabled" in {

      val settings = GroupRouterSpecSettings(node1WorkerCount = 2, node2WorkerCount = 2, messageCount = 100)

      val groupRouters = List(
        Routers.group(pingPongKey),
        Routers.group(pingPongKey).withRandomRouting(),
        Routers.group(pingPongKey).withRoundRobinRouting(),
        Routers.group(pingPongKey).withRoundRobinRouting(false),
        Routers.group(pingPongKey).withRandomRouting(false))

      groupRouters.foreach { groupRouter =>
        checkGroupRouterBehavior(groupRouter, settings) {
          case (actorRefsInNode1, actorRefsInNode2) =>
            (actorRefsInNode1.size + actorRefsInNode2.size) shouldBe settings.messageCount
            actorRefsInNode1.toSet.size shouldBe settings.node1WorkerCount
            actorRefsInNode2.toSet.size shouldBe settings.node2WorkerCount
        }
      }
    }

    "only use local routees if preferLocalRoutees is enabled and there are local routees" in {
      val settings = GroupRouterSpecSettings(node1WorkerCount = 2, node2WorkerCount = 2, messageCount = 100)

      val groupRouters =
        List(Routers.group(pingPongKey).withRoundRobinRouting(true), Routers.group(pingPongKey).withRandomRouting(true))

      groupRouters.foreach { groupRouter =>
        checkGroupRouterBehavior(groupRouter, settings) {
          case (actorRefsInNode1, actorRefsInNode2) =>
            actorRefsInNode1.size shouldBe settings.messageCount
            actorRefsInNode1.toSet.size shouldBe settings.node1WorkerCount
            actorRefsInNode2.toSet.size shouldBe 0
        }
      }
    }

    "use remote routees if preferLocalRoutees is enabled but there is no local routees" in {
      val settings = GroupRouterSpecSettings(node1WorkerCount = 0, node2WorkerCount = 2, messageCount = 100)

      val groupRouters =
        List(Routers.group(pingPongKey).withRoundRobinRouting(true), Routers.group(pingPongKey).withRandomRouting(true))

      groupRouters.foreach { groupRouter =>
        checkGroupRouterBehavior(groupRouter, settings) {
          case (actorRefsInNode1, actorRefsInNode2) =>
            actorRefsInNode2.size shouldBe settings.messageCount
            actorRefsInNode1.toSet.size shouldBe 0
            actorRefsInNode2.toSet.size shouldBe settings.node2WorkerCount
        }
      }
    }
  }
}
