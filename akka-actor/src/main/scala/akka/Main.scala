/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Actor
import akka.actor.Terminated
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.ActorRef
import scala.util.control.NonFatal

object Main {

  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      println("you need to provide exactly one argument: the class of the application supervisor actor")
    } else {
      val system = ActorSystem("Main")
      try {
        val appClass = system.asInstanceOf[ExtendedActorSystem].dynamicAccess.getClassFor[Actor](args(0)).get
        val app = system.actorOf(Props(appClass), "app")
        val terminator = system.actorOf(Props(classOf[Terminator], app), "app-terminator")
      } catch {
        case NonFatal(e) ⇒ system.shutdown(); throw e
      }
    }
  }

  class Terminator(app: ActorRef) extends Actor with ActorLogging {
    context watch app
    def receive = {
      case Terminated(_) ⇒
        log.info("application supervisor has terminated, shutting down")
        context.system.shutdown()
    }
  }

}
