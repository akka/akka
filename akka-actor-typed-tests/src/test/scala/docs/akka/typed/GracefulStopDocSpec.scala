/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

//#imports
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, PostStop }

//#imports

import org.slf4j.Logger
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.WordSpecLike

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

object GracefulStopDocSpec {

  //#master-actor

  object MasterControlProgram {
    sealed trait Command
    final case class SpawnJob(name: String) extends Command
    final case object GracefulShutdown extends Command

    // Predefined cleanup operation
    def cleanup(log: Logger): Unit = log.info("Cleaning up!")

    def apply(): Behavior[Command] = {
      Behaviors
        .receive[Command] { (context, message) =>
          message match {
            case SpawnJob(jobName) =>
              context.log.info("Spawning job {}!", jobName)
              context.spawn(Job(jobName), name = jobName)
              Behaviors.same
            case GracefulShutdown =>
              context.log.info("Initiating graceful shutdown...")
              // perform graceful stop, executing cleanup before final system termination
              // behavior executing cleanup is passed as a parameter to Actor.stopped
              Behaviors.stopped { () =>
                cleanup(context.system.log)
              }
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

    def apply(name: String): Behavior[Command] = {
      Behaviors.receiveSignal[Command] {
        case (context, PostStop) =>
          context.log.info("Worker {} stopped", name)
          Behaviors.same
      }
    }
  }
  //#worker-actor

}

class GracefulStopDocSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {

  import GracefulStopDocSpec._

  "Graceful stop example" must {

    "start some workers" in {
      //#start-workers
      import MasterControlProgram._

      val system: ActorSystem[Command] = ActorSystem(MasterControlProgram(), "B6700")

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

      val system: ActorSystem[Command] = ActorSystem(MasterControlProgram(), "B7700")

      system ! SpawnJob("a")
      system ! SpawnJob("b")

      Thread.sleep(100)

      // gracefully stop the system
      system ! GracefulShutdown

      Thread.sleep(100)

      Await.result(system.whenTerminated, 3.seconds)
      //#graceful-shutdown
    }
  }
}
