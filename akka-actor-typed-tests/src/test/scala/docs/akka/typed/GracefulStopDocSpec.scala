/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

//#imports
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Logger, PostStop }
import org.scalatest.WordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

//#imports

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

object GracefulStopDocSpec {

  //#master-actor

  object MasterControlProgramActor {
    sealed trait JobControlLanguage
    final case class SpawnJob(name: String) extends JobControlLanguage
    final case object GracefulShutdown extends JobControlLanguage

    // Predefined cleanup operation
    def cleanup(log: Logger): Unit = log.info("Cleaning up!")

    val mcpa = Behaviors
      .receive[JobControlLanguage] { (context, message) =>
        message match {
          case SpawnJob(jobName) =>
            context.log.info("Spawning job {}!", jobName)
            context.spawn(Job.job(jobName), name = jobName)
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
          context.log.info("MCPA stopped")
          Behaviors.same
      }
  }
  //#master-actor

  //#worker-actor

  object Job {
    import GracefulStopDocSpec.MasterControlProgramActor.JobControlLanguage

    def job(name: String) = Behaviors.receiveSignal[JobControlLanguage] {
      case (context, PostStop) =>
        context.log.info("Worker {} stopped", name)
        Behaviors.same
    }
  }
  //#worker-actor

}

class GracefulStopDocSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import GracefulStopDocSpec._

  "Graceful stop example" must {

    "start some workers" in {
      //#start-workers
      import MasterControlProgramActor._

      val system: ActorSystem[JobControlLanguage] = ActorSystem(mcpa, "B6700")

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

      import MasterControlProgramActor._

      val system: ActorSystem[JobControlLanguage] = ActorSystem(mcpa, "B7700")

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
