package sample.eventstream

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Terminated

object ListeningActor {
  def props(): Props = Props(new ListeningActor())
}

class ListeningActor extends Actor with ActorLogging {
  context.system.eventStream.subscribe(self, classOf[DyingActor.Notification])

  def receive = {
    case DyingActor.Notification(msg) => log.info("Received Notification({})", msg)
  }

  override def postStop(): Unit = {
    log.info("Listener dying!")
    context.system.eventStream.unsubscribe(self)
  }
}

