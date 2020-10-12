/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

//#imports
import akka.actor.typed.{ ActorSystem, Behavior, PostStop }
import akka.actor.typed.scaladsl.Behaviors

import scala.collection.immutable.Seq

//#imports

import akka.actor.testkit.typed.scaladsl.LogCapturing
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
    sealed trait Command
    final case class SpawnJob(name: String) extends Command
    case object GracefulShutdown extends Command
    final case class Cleaned(actor: ActorRef[Job.Command]) extends Command

    // Predefined cleanup operation

    def apply(jobs: Seq[ActorRef[Job.Command]]): Behavior[Command] = {
      Behaviors
        .receive[Command] { (context, message) =>
          message match {
            case SpawnJob(jobName) =>
              context.log.info("Spawning job {}!", jobName)
              val job = context.spawn(Job(jobName), name = jobName)
              job ! Job.Shutdown(context.self)
              apply(job +: jobs)
            case GracefulShutdown =>
              context.log.info("Initiating graceful shutdown...")
              // perform graceful stop, executing cleanup before final system termination
              // behavior executing cleanup is passed as a parameter to Actor.stopped
              jobs.map(_ ! Job.Shutdown(context.self))
              Behaviors.same
            case Cleaned(job) =>
              val runningJobs = jobs.filterNot(_.path == job.path)
              if (runningJobs.isEmpty)
                Behaviors.stopped
              else
                apply(runningJobs)
          }
        }
        .receiveSignal {
          case (context, PostStop) =>
            context.log.info("Master Control Program stopped")
            Behaviors.same
        }
    }
  }
  //#master-actor

  //#worker-actor

  object Job {
    sealed trait Command
    case class Shutdown(replyTo: ActorRef[MasterControlProgram.Command]) extends Command

    def cleanup(log: Logger): Unit = log.info("Cleaning up!")

    def apply(name: String): Behavior[Command] = {
      Behaviors
        .receive[Command] { (context, message) =>
          message match {
            case Shutdown(replyTo: ActorRef[MasterControlProgram.Command]) =>
              Behaviors.stopped { () =>
                cleanup(context.system.log)
                replyTo ! MasterControlProgram.Cleaned(context.self)
              }
          }
        }
        .receiveSignal {
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

      val system: ActorSystem[Command] = ActorSystem(MasterControlProgram(Seq.empty), "B6700")

      system ! SpawnJob("a")
      system ! SpawnJob("b")

      // sleep here to allow time for the new actors to be started
      Thread.sleep(100)

      // brutally stop the system
      system.terminate()

      Await.result(system.whenTerminated, 3.seconds)
      //#start-workers
    }

    "gracefully stop workers and master" in {
      //#graceful-shutdown

      import MasterControlProgram._

      val system: ActorSystem[Command] = ActorSystem(MasterControlProgram(Seq.empty), "B7700")

      system ! SpawnJob("a")
      system ! SpawnJob("b")

      Thread.sleep(100)

      // gracefully stop the system
      system ! GracefulShutdown

      Thread.sleep(100)

      Await.result(system.whenTerminated, 12.seconds)
      //#graceful-shutdown
    }
  }
}
