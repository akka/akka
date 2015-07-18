package sample.eventstream

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Cancellable

object DyingActor {
  def props(ttl: FiniteDuration): Props = Props(new DyingActor(ttl))
  case class DyingCry(message: String)
}

class DyingActor(timeToLive: FiniteDuration) extends Actor with ActorLogging {
  import DyingActor._
  import context.dispatcher

  // scheduled to die
  val scheduler: Cancellable = context.system.scheduler.scheduleOnce(timeToLive)(die)

  def die() = {
    context.system.eventStream.publish(DyingCry(s"${context.parent} let me die after $timeToLive!"))
    scheduler.cancel
    context.stop(self)
  }

  def receive = {
    case _ =>
  }
}

