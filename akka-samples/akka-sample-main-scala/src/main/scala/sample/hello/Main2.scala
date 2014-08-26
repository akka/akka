package sample.hello

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Terminated

object Main2 {

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("Hello")
    val a = system.actorOf(Props[HelloWorld], "helloWorld")
    system.actorOf(Props(classOf[Terminator], a), "terminator")
  }

  class Terminator(ref: ActorRef) extends Actor with ActorLogging {
    context watch ref
    def receive = {
      case Terminated(_) =>
        log.info("{} has terminated, shutting down system", ref.path)
        context.system.terminate()
    }
  }

}