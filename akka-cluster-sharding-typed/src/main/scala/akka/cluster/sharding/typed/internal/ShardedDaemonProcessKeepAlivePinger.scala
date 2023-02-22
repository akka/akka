/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.annotation.InternalApi
import akka.cluster.MemberStatus
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.StartEntity
import akka.cluster.typed.Cluster
import akka.cluster.typed.SelfUp
import akka.cluster.typed.Subscribe
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ShardedDaemonProcessKeepAlivePinger {
  sealed trait Event

  private case object Tick extends Event

  private case object SendKeepAliveDone extends Event

  def apply[T](
      settings: ShardedDaemonProcessSettings,
      name: String,
      initialNumberOfInstances: Int,
      shardingRef: ActorRef[ShardingEnvelope[T]]): Behavior[Event] = {

    // FIXME subscribe to ddata state and stop pinging when new revision and not complete yet,
    //       then start pinging new revision once complete
    val revision = 0
    val identities = (0 until initialNumberOfInstances).map(n =>
      ShardedDaemonProcessImpl.DecodedId(revision, initialNumberOfInstances, n).encodeEntityId)
    val sortedIdentities = identities.toVector.sorted

    def sendKeepAliveMessages()(implicit sys: ActorSystem[_]): Future[Done] = {
      if (settings.keepAliveThrottleInterval == Duration.Zero) {
        sortedIdentities.foreach(id => shardingRef ! StartEntity(id))
        Future.successful(Done)
      } else {
        Source(sortedIdentities).throttle(1, settings.keepAliveThrottleInterval).runForeach { id =>
          shardingRef ! StartEntity(id)
        }
      }
    }

    Behaviors.setup[Event] { context =>
      implicit val system: ActorSystem[_] = context.system
      val cluster = Cluster(system)

      if (cluster.selfMember.status == MemberStatus.Up)
        context.self ! Tick
      else
        cluster.subscriptions ! Subscribe(context.messageAdapter[SelfUp](_ => Tick), classOf[SelfUp])

      def isActive(): Boolean = {
        val members = settings.role match {
          case None       => cluster.state.members
          case Some(role) => cluster.state.members.filter(_.roles.contains(role))
        }
        // members are sorted so this is deterministic (the same) on all nodes
        members.take(settings.keepAliveFromNumberOfNodes).contains(cluster.selfMember)
      }

      Behaviors.withTimers { timers =>
        Behaviors.receiveMessage {
          case Tick =>
            if (isActive()) {
              context.log.debug2(
                s"Sending periodic keep alive for Sharded Daemon Process [{}] to [{}] processes.",
                name,
                sortedIdentities.size)
              context.pipeToSelf(sendKeepAliveMessages()) { _ =>
                SendKeepAliveDone
              }
            } else {
              timers.startSingleTimer(Tick, settings.keepAliveInterval)
            }
            Behaviors.same
          case SendKeepAliveDone =>
            timers.startSingleTimer(Tick, settings.keepAliveInterval)
            Behaviors.same
        }
      }
    }
  }

}
