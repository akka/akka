package com.lightbend

import akka.actor.Address
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Routers
import akka.cluster.ddata.GSet
import akka.cluster.ddata.GCounterKey
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.typed.ClusterSingleton
import akka.cluster.typed.SingletonActor
import akka.serialization.jackson.CborSerializable
import akka.serialization.jackson.JsonSerializable
import akka.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

final case class Response(message: String, address: Address) extends CborSerializable

object PingPong {
  val serviceKey = ServiceKey[PingPong.Ping]("PingPong")

  final case class Ping(replyTo: ActorRef[Response], from: Address) extends JsonSerializable

  def apply(): Behavior[Ping] = Behaviors.receive { (context, message) =>
    context.log.trace("Saw ping from {}", message.from)
    message.replyTo ! Response("Typed actor reply", context.system.address)
    Behaviors.same
  }
}

object PingEmAll {
  def apply(whenDone: ActorRef[String], expectedNodeCount: Int): Behavior[AnyRef] = Behaviors.setup { context =>
    val router = context.spawn(Routers.group(PingPong.serviceKey), "PingPongRouter")

    Behaviors.withTimers { timers =>
      implicit val timeout: Timeout = 3.seconds
      timers.startTimerWithFixedDelay("Tick", 300.millis)
      context.self ! "Tick"

      var seenAddresses = Set[Address]()

      Behaviors.receiveMessage {
        case "Tick" =>
          context.ask(router, PingPong.Ping(_, context.system.address)) {
            case Success(value)     => value
            case Failure(exception) => exception.getMessage
          }
          Behaviors.same

        case Response(message, address) =>
          context.log.debug("Got response {} from address {}", message, address)
          seenAddresses += address
          if (seenAddresses.size == expectedNodeCount) {
            context.log.debug("Saw responses from all nodes, shutting down")
            whenDone ! "Pinged all nodes and saw responses"
            Behaviors.stopped
          } else {
            Behaviors.same
          }

        case error: String =>
          // probably a timeout
          context.log.debug("Saw error {}", error)
          Behaviors.same

      }

    }
  }
}

object ShardingCheck {
  val entityType = EntityTypeKey[PingPong.Ping]("PingAndAPong")
  val entity = Entity(entityType)(_ => PingPong())

  def apply(whenDone: ActorRef[String], expectedNodeCount: Int): Behavior[AnyRef] = Behaviors.setup { context =>
    val sharding = ClusterSharding(context.system)

    sharding.init(entity)

    var seenAddresses = Set[Address]()

    Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay("Tick", 50.millis)
      context.self ! "Tick"

      // roll over different ids to eventually reach all nodes
      var counter = 0

      Behaviors.receiveMessage {
        case "Tick" =>
          implicit val timeout: Timeout = 3.seconds
          counter += 1
          val entityRef = sharding.entityRefFor(entityType, counter.toString)
          context.pipeToSelf(entityRef.ask(PingPong.Ping(_, context.system.address))) {
            case Success(value)     => value
            case Failure(exception) => exception.getMessage
          }
          Behaviors.same
        case Response(message, address) =>
          context.log.debug("Got response {} from address {}", message, address)
          seenAddresses += address
          if (seenAddresses.size == expectedNodeCount) {
            context.log.debug("Saw responses from all nodes, shutting down")
            whenDone ! "Pinged all nodes over sharding and saw responses"
            Behaviors.stopped
          } else {
            Behaviors.same
          }

        case error: String =>
          // probably a timeout
          context.log.debug("Saw error {}", error)
          Behaviors.same
      }
    }
  }
}

object SingletonCheck {
  def apply(whenDone: ActorRef[String]): Behavior[AnyRef] = Behaviors.setup { context =>
    implicit val timeout: Timeout = 3.seconds
    val proxy = ClusterSingleton(context.system).init(SingletonActor(PingPong(), "PingPongSingleton"))

    Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay("Tick", 200.millis)
      context.self ! "Tick"

      Behaviors.receiveMessage {
        case "Tick" =>
          context.ask(proxy, PingPong.Ping(_, context.system.address)) {
            case Success(value)     => value
            case Failure(exception) => exception.getMessage
          }
          Behaviors.same

        case Response(message, address) =>
          context.log.debug("Got singleton response {} from address {}, shutting down", message, address)
          whenDone ! "Pinged singleton and saw response"
          Behaviors.stopped

        case error: String =>
          // probably a timeout
          context.log.debug("Saw error {}", error)
          Behaviors.same

      }

    }
  }
}

object DdataCheck {

  val key = GSetKey[String]("nodes")

  def apply(whenDone: ActorRef[String], expectedNodeCount: Int): Behavior[AnyRef] = Behaviors.setup { context =>
    implicit val node: SelfUniqueAddress = DistributedData(context.system).selfUniqueAddress

    DistributedData.withReplicatorMessageAdapter[AnyRef, GSet[String]] { replicatorAdapter =>
      // Subscribe to changes of the given `key`.
      replicatorAdapter.subscribe(key, identity)

      // write local and let it eventually replicate
      replicatorAdapter.askUpdate(
        replyTo =>
          Replicator.Update(key, GSet.empty[String], Replicator.WriteLocal, replyTo)(set =>
            set.add(context.system.address.toString)),
        identity)

      Behaviors.receiveMessage[AnyRef] {
        case changed @ Replicator.Changed(`key`) =>
          val elements = changed.get(key).elements
          context.log.debug("Seen ddata entries: {}", elements)
          if (elements.size == expectedNodeCount) {
            whenDone ! "DData saw entries from all nodes"
            Behaviors.stopped
          } else {
            Behaviors.same
          }
        case d =>
          Behaviors.same

      }
    }

  }
}

object RootBehavior {
  def apply(): Behavior[AnyRef] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      timers.startSingleTimer("Timeout", 30.seconds)
      val expectedNodes = 2

      // Note that this check uses the Receptionist, so covers that and Ddata as it is the underlying mechanism
      val localPingPong = context.spawn(PingPong(), "PingPong")
      context.system.receptionist ! Receptionist.Register(PingPong.serviceKey, localPingPong)
      context.spawn(PingEmAll(context.self, expectedNodes), "PingEmAll")

      // sharding
      context.spawn(ShardingCheck(context.self, expectedNodes), "ShardingPingPong")

      // singleton
      context.spawn(SingletonCheck(context.self), "SingletonPingPong")

      // ddata
      context.spawn(DdataCheck(context.self, expectedNodes), "DData")

      var expectedResponses =
        Set(
          "Pinged all nodes and saw responses",
          "Pinged all nodes over sharding and saw responses",
          "Pinged singleton and saw response",
          "DData saw entries from all nodes")

      Behaviors.receiveMessage {
        case "Timeout" =>
          context.log.error("Timed out with expected responses left: {}", expectedResponses.mkString(", "))
          System.exit(1)
          Behaviors.stopped

        case "Stop" =>
          context.log.info("Successfully completed, stopping")
          Behaviors.stopped

        case message: String =>
          expectedResponses -= message
          if (expectedResponses.isEmpty) {
            // Note delay so that the other node also can complete
            context.log.info("All checks completed, stopping in 10s")
            timers.startSingleTimer("Stop", 10.seconds)
            Behaviors.same
          } else {
            Behaviors.same
          }
        case other =>
          context.log.error("Unexpected message {}", other)
          Behaviors.stopped
      }
    }
  }
}

object Main {

  def main(args: Array[String]): Unit = {
    ActorSystem(RootBehavior(), "AkkaNativeClusterTest")
  }

}
