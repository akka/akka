/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.{ Actor, ActorLogging, ActorRef, PoisonPill, Props }
import akka.cluster.Cluster
import akka.cluster.sharding.ShardRegion.Passivate
import akka.pattern.{ Backoff, BackoffSupervisor }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object SupervisionSpec {
  val config =
    ConfigFactory.parseString(
      """
    akka.actor.provider = "cluster"
    akka.loglevel = INFO
    """)

  case class Msg(id: Long, msg: Any)
  case class Response(self: ActorRef)
  case object StopMessage

  val idExtractor: ShardRegion.ExtractEntityId = {
    case Msg(id, msg) ⇒ (id.toString, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId = {
    case Msg(id, msg) ⇒ (id % 2).toString
  }

  class PassivatingActor extends Actor with ActorLogging {

    override def preStart(): Unit = {
      log.info("Starting")
    }

    override def postStop(): Unit = {
      log.info("Stopping")
    }

    override def receive: Receive = {
      case "passivate" ⇒
        log.info("Passivating")
        context.parent ! Passivate(StopMessage)
        // simulate another message causing a stop before the region sends the stop message
        // e.g. a persistent actor having a persist failure while processing the next message
        context.stop(self)
      case "hello" ⇒
        sender() ! Response(self)
      case StopMessage ⇒
        log.info("Received stop from region")
        context.parent ! PoisonPill
    }
  }

}

class SupervisionSpec extends AkkaSpec(SupervisionSpec.config) with ImplicitSender {

  import SupervisionSpec._

  "Supervision for a sharded actor" must {

    "allow passivation" in {

      val supervisedProps = BackoffSupervisor.props(Backoff.onStop(
        Props(new PassivatingActor()),
        childName = "child",
        minBackoff = 1.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.2,
        maxNrOfRetries = -1
      ).withFinalStopMessage(_ == StopMessage))

      Cluster(system).join(Cluster(system).selfAddress)
      val region = ClusterSharding(system).start(
        "passy",
        supervisedProps,
        ClusterShardingSettings(system),
        idExtractor,
        shardResolver
      )

      region ! Msg(10, "hello")
      val response = expectMsgType[Response](5.seconds)
      watch(response.self)

      region ! Msg(10, "passivate")
      expectTerminated(response.self)

      // This would fail before as sharded actor would be stuck passivating
      region ! Msg(10, "hello")
      expectMsgType[Response](20.seconds)
    }
  }

}
