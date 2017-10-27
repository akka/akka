/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.akka.typed

//#imports
import akka.typed._
import akka.typed.scaladsl.Actor
import scala.concurrent.duration._
import scala.concurrent.Await
//#imports

object GracefulStopDocSpec {

  //#master-actor
  object MasterControlProgramActor {
    sealed trait JobControlLanguage
    final case class SpawnJob(name: String) extends JobControlLanguage
    final case object GracefulShutdown extends JobControlLanguage

    // Predefined cleanup operation
    def cleanup[T](ctx: scaladsl.ActorContext[T]): Unit = ctx.system.log.info("Cleaning up!")

    val mcpa = Actor.immutable[JobControlLanguage] { (ctx, msg) ⇒
      msg match {
        case SpawnJob(jobName) ⇒
          ctx.system.log.info("Spawning job {}!", jobName)
          ctx.spawn(Job.job(jobName), jobName)
          Actor.same
        case GracefulShutdown ⇒
          ctx.system.log.info("Initiating graceful shutdown...")
          // perform graceful stop, executing cleanup before final system termination
          // behavior executing cleanup is passed as a parameter to Actor.stopped
          Actor.stopped {
            Actor.onSignal {
              case (context, PostStop) ⇒
                cleanup(context)
                Actor.same
            }
          }
      }
    } onSignal {
      case (ctx, PostStop) ⇒
        ctx.system.log.info("MCPA stopped")
        Actor.same
    }
  }
  //#master-actor

  //#worker-actor
  object Job {
    import GracefulStopDocSpec.MasterControlProgramActor.JobControlLanguage

    def job(name: String) = Actor.onSignal[JobControlLanguage] {
      case (ctx, PostStop) ⇒
        ctx.system.log.info("Worker {} stopped", name)
        Actor.same
    }
  }
  //#worker-actor

}

class GracefulStopDocSpec extends TypedSpec {
  import GracefulStopDocSpec._

  def `must start some workers`(): Unit = {
    // TODO Implicits.global is not something we would like to encourage in docs
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

  def `must gracefully stop workers and master`(): Unit = {
    // TODO Implicits.global is not something we would like to encourage in docs
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
