package sample.eventstream

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Cancellable

object DyingActor {
  def props(ttl: FiniteDuration): Props = Props(new DyingActor(ttl))
  case class Notification(message: String)
}

class DyingActor(timeToLive: FiniteDuration) extends Actor with ActorLogging {
  import DyingActor._
  import context.dispatcher

  // scheduled to die
  val scheduler: Cancellable = context.system.scheduler.scheduleOnce(timeToLive, self, "die")

  def die() = {
    context.system.eventStream.publish(Notification(s"${context.parent} let me die after $timeToLive!"))
    context.stop(self)
  }

  def receive = {
    case "die" => die()
    case _ =>
  }
}

