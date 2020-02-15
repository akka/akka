/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

//#imports
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, PostStop}

//#imports

import akka.actor.typed.ActorRef
import org.slf4j.Logger
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.wordspec.AnyWordSpecLike
import akka.actor.typed.Terminated

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

object GracefulStopDocSpec {

  //#master-actor

  object MasterControlProgram {

    def apply(): Behavior[Command] = {
      Behaviors.setup { context =>
        val manager = context.spawn(Manager(), "managerName")

        Behaviors
          .receive[Command] { (context, message) =>
            message match {
              case Tasks(tasks) =>
                context.log.info("ToDo list {}!", tasks)
                tasks.map(manager ! Manager.SpawnJob(_))
                Behaviors.same
              case Stop =>
                manager ! Manager.GracefulShutdown(context.self)
                Behaviors.same
              case Clean =>
                cleanup(context.log)
                Behaviors.stopped
            }
          }
          .receiveSignal {
            case (context, PostStop) =>
              context.log.info("Master Control Program stopped")
              Behaviors.same
          }
      }
    }

    // Predefined cleanup operation
    def cleanup(log: Logger): Unit = log.info("Cleaning up!")

    sealed trait Command

    final case class Tasks(names: List[String]) extends Command

    final case object Stop extends Command

    final case object Clean extends Command
  }
  //#master-actor

  //#manager-actor
  object Manager {
    def apply(): Behavior[Command] = {
      Behaviors.receive[Command] { (context, message) =>
        message match {
          case SpawnJob(jobName) =>
            context.log.info("Spawning job {}!", jobName)
            context.spawn(Job(jobName), name = jobName)
            Behaviors.same
          case GracefulShutdown(replyTo: ActorRef[MasterControlProgram.Command]) =>
            context.log.info("Initiating graceful shutdown...")
            // perform graceful stop, executing cleanup before final system termination
            // behavior executing cleanup is passed as a parameter to Actor.stopped
            Behaviors.stopped { () =>
              replyTo ! MasterControlProgram.Clean
            }
        }
      }
    }

    sealed trait Command

    final case class SpawnJob(name: String) extends Command

    final case class GracefulShutdown(replyTo: ActorRef[MasterControlProgram.Command]) extends Command
  }
  //#manager-actor

  //#worker-actor

  object Job {
    sealed trait Command

    def apply(name: String): Behavior[Command] = {
      Behaviors.receiveSignal[Command] {
        case (context, PostStop) =>
          context.log.info("Worker {} stopped", name)
          Behaviors.same
      }
    }
  }
  //#worker-actor

  object IllustrateWatch {
    //#master-actor-watch

    object MasterControlProgram {
      sealed trait Command
      final case class SpawnJob(name: String) extends Command

      def apply(): Behavior[Command] = {
        Behaviors
          .receive[Command] { (context, message) =>
            message match {
              case SpawnJob(jobName) =>
                context.log.info("Spawning job {}!", jobName)
                val job = context.spawn(Job(jobName), name = jobName)
                context.watch(job)
                Behaviors.same
            }
          }
          .receiveSignal {
            case (context, Terminated(ref)) =>
              context.log.info("Job stopped: {}", ref.path.name)
              Behaviors.same
          }
      }
    }
    //#master-actor-watch
  }

  object IllustrateWatchWith {
    //#master-actor-watchWith

    object MasterControlProgram {
      sealed trait Command
      final case class SpawnJob(name: String, replyToWhenDone: ActorRef[JobDone]) extends Command
      final case class JobDone(name: String)
      private final case class JobTerminated(name: String, replyToWhenDone: ActorRef[JobDone]) extends Command

      def apply(): Behavior[Command] = {
        Behaviors.receive { (context, message) =>
          message match {
            case SpawnJob(jobName, replyToWhenDone) =>
              context.log.info("Spawning job {}!", jobName)
              val job = context.spawn(Job(jobName), name = jobName)
              context.watchWith(job, JobTerminated(jobName, replyToWhenDone))
              Behaviors.same
            case JobTerminated(jobName, replyToWhenDone) =>
              context.log.info("Job stopped: {}", jobName)
              replyToWhenDone ! JobDone(jobName)
              Behaviors.same
          }
        }
      }
    }
    //#master-actor-watchWith
  }

}

class GracefulStopDocSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  import GracefulStopDocSpec._

  "Graceful stop example" must {

    "start some workers" in {
      //#start-workers
      import MasterControlProgram._

      val system: ActorSystem[Command] = ActorSystem(MasterControlProgram(), "B6700")

      system ! Tasks(List("a", "b"))

      // sleep here to allow time for the new actors to be started
      Thread.sleep(100)

      // brutally stop the system
      system.terminate()

      Await.result(system.whenTerminated, 3.seconds)
      //#start-workers
    }

    "gracefully stop workers and master" in {
      import MasterControlProgram._

      implicit val system: ActorSystem[Command] = ActorSystem(MasterControlProgram(), "B7700")

      system ! Tasks(List("a", "b"))

      Thread.sleep(100)

      // gracefully stop the system
      system ! Stop

      Thread.sleep(100)

      Await.result(system.whenTerminated, 6.seconds)
      //#graceful-shutdown
    }
  }
}
