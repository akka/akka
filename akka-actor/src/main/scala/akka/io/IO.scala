/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.actor._
import akka.routing.RandomRouter
import akka.io.SelectionHandler.WorkerForCommand

object IO {

  trait Extension extends akka.actor.Extension {
    def manager: ActorRef
  }

  def apply[T <: Extension](key: ExtensionKey[T])(implicit system: ActorSystem): ActorRef = key(system).manager

  trait HasFailureMessage {
    def failureMessage: Any
  }

  abstract class SelectorBasedManager(selectorSettings: SelectionHandlerSettings, nrOfSelectors: Int) extends Actor {

    val selectorPool = context.actorOf(
      props = Props(new SelectionHandler(self, selectorSettings)).withRouter(RandomRouter(nrOfSelectors)),
      name = "selectors")

    private def createWorkerMessage(pf: PartialFunction[HasFailureMessage, Props]): PartialFunction[HasFailureMessage, WorkerForCommand] = {
      case cmd ⇒
        val props = pf(cmd)
        val commander = sender
        WorkerForCommand(cmd, commander, props)
    }

    def workerForCommand(pf: PartialFunction[Any, Props]): Receive = {
      case cmd: HasFailureMessage if pf.isDefinedAt(cmd) ⇒ selectorPool ! createWorkerMessage(pf)(cmd)
    }
  }

}