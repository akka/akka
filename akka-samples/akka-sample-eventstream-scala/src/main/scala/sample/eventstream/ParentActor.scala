package sample.eventstream

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Terminated

class ParentActor extends Actor with ActorLogging {
  import context.dispatcher

  override def preStart(): Unit = {
    // start listening actor that subscribes itself to the eventstream
    context.actorOf(ListeningActor.props(), "listener")
    // spawn 10 to 20 actors
    val nActors = 10 + scala.util.Random.nextInt(10)
    for (i <- 1 to nActors) {
      val timeToLive = scala.util.Random.nextFloat.seconds
      context.watch(context.actorOf(DyingActor.props(timeToLive), s"dyingActor$i"))
    }
  }

  def receive = {
    case Terminated(ref) =>
      log.info("{} terminated, {} children left", ref, context.children.size)
      if (context.children.size == 1) {
        // stop the listening actor
        context.children.foreach(context.stop)
        log.info("Parent dying!")
        context.stop(self)
      }
  }
}

