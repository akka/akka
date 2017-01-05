/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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

/**
 * Main class to start an [[akka.actor.ActorSystem]] with one
 * top level application supervisor actor. It will shutdown
 * the actor system when the top level actor is terminated.
 */
object Main {

  /**
   * @param args one argument: the class of the application supervisor actor
   */
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
        case NonFatal(e) ⇒ system.terminate(); throw e
      }
    }
  }

  class Terminator(app: ActorRef) extends Actor with ActorLogging {
    context watch app
    def receive = {
      case Terminated(_) ⇒
        log.info("application supervisor has terminated, shutting down")
        context.system.terminate()
    }
  }

}
