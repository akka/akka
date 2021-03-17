/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.{ Actor, ActorLogging, ActorRef, PoisonPill, Props }
import akka.cluster.Cluster
import akka.cluster.sharding.ShardRegion.Passivate
import akka.pattern.{ BackoffOpts, BackoffSupervisor }
import akka.testkit.EventFilter
import akka.testkit.WithLogCapturing
import akka.testkit.{ AkkaSpec, ImplicitSender }

object SupervisionSpec {
  val config =
    ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.remote.artery.canonical.port = 0
    akka.remote.classic.netty.tcp.port = 0
    akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
    akka.loglevel = DEBUG
    akka.cluster.sharding.verbose-debug-logging = on
    akka.cluster.sharding.fail-on-invalid-entity-state-transition = on
    """)

  case class Msg(id: Long, msg: Any)
  case class Response(self: ActorRef)
  case object StopMessage

  val idExtractor: ShardRegion.ExtractEntityId = {
    case Msg(id, msg) => (id.toString, msg)
    case _ => throw new IllegalArgumentException()
  }

  val shardResolver: ShardRegion.ExtractShardId = {
    case Msg(id, _) => (id % 2).toString
    case _ => throw new IllegalArgumentException()
  }

  class PassivatingActor extends Actor with ActorLogging {

    override def preStart(): Unit = {
      log.info("Starting")
    }

    override def postStop(): Unit = {
      log.info("Stopping")
    }

    override def receive: Receive = {
      case "passivate" =>
        log.info("Passivating")
        context.parent ! Passivate(StopMessage)
        // simulate another message causing a stop before the region sends the stop message
        // e.g. a persistent actor having a persist failure while processing the next message
        // note that this means the StopMessage will go to dead letters
        context.stop(self)
      case "hello" =>
        sender() ! Response(self)
      case StopMessage =>
        // note that we never see this because we stop early
        log.info("Received stop from region")
        context.parent ! PoisonPill
    }
  }

}

class DeprecatedSupervisionSpec extends AkkaSpec(SupervisionSpec.config) with ImplicitSender with WithLogCapturing {
  import SupervisionSpec._

  "Supervision for a sharded actor (deprecated)" must {

    "allow passivation and early stop" in {

      val supervisedProps =
        BackoffOpts
          .onStop(
            Props(new PassivatingActor()),
            childName = "child",
            minBackoff = 1.seconds,
            maxBackoff = 30.seconds,
            randomFactor = 0.2)
          .withFinalStopMessage(_ == StopMessage)
          .props

      Cluster(system).join(Cluster(system).selfAddress)
      val region = ClusterSharding(system).start(
        "passy",
        supervisedProps,
        ClusterShardingSettings(system),
        idExtractor,
        shardResolver)

      region ! Msg(10, "hello")
      val response = expectMsgType[Response](5.seconds)
      watch(response.self)

      // We need the shard to have observed the passivation for this test but
      // we don't know that this means the passivation reached the shard yet unless we observe it
      EventFilter.debug("passy: Passivation started for [10]", occurrences = 1).intercept {
        region ! Msg(10, "passivate")
        expectTerminated(response.self)
      }

      // This would fail before as sharded actor would be stuck passivating
      region ! Msg(10, "hello")
      expectMsgType[Response](20.seconds)
    }
  }
}

class SupervisionSpec extends AkkaSpec(SupervisionSpec.config) with ImplicitSender {

  import SupervisionSpec._

  "Supervision for a sharded actor" must {

    "allow passivation and early stop" in {

      val supervisedProps = BackoffSupervisor.props(
        BackoffOpts
          .onStop(
            Props(new PassivatingActor()),
            childName = "child",
            minBackoff = 1.seconds,
            maxBackoff = 30.seconds,
            randomFactor = 0.2)
          .withFinalStopMessage(_ == StopMessage))

      Cluster(system).join(Cluster(system).selfAddress)
      val region = ClusterSharding(system).start(
        "passy",
        supervisedProps,
        ClusterShardingSettings(system),
        idExtractor,
        shardResolver)

      region ! Msg(10, "hello")
      val response = expectMsgType[Response](5.seconds)
      watch(response.self)

      // 1. passivation message is passed on from supervisor to shard (which starts buffering messages for the entity id)
      // 2. child stops
      // 3. the supervisor has or has not yet seen gotten the stop message back from the shard
      //   a. if has it will stop immediatel, and the next message will trigger the shard to restart it
      //   b. if it hasn't the supervisor will back off before restarting the child, when the
      //     final stop message `StopMessage` comes in from the shard it will stop itself
      // 4. when the supervisor stops the shard should start it anew and deliver the buffered messages
      region ! Msg(10, "passivate")
      expectTerminated(response.self)

      region ! Msg(10, "hello")
      expectMsgType[Response](20.seconds)
    }
  }

}
