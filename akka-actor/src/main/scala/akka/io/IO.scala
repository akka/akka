/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.actor._
import akka.routing.RandomRouter
import akka.io.SelectionHandler.KickStartCommand

object IO {

  trait Extension extends akka.actor.Extension {
    def manager: ActorRef
  }

  def apply[T <: Extension](key: ExtensionKey[T])(implicit system: ActorSystem): ActorRef = key(system).manager

  abstract class SelectorBasedManager(selectorSettings: SelectionHandlerSettings, nrOfSelectors: Int) extends Actor {

    val selectorPool = context.actorOf(
      props = Props(new SelectionHandler(self, selectorSettings)).withRouter(RandomRouter(nrOfSelectors)),
      name = "selectors")

    def createKickStart(pf: PartialFunction[Any, Props], cmd: Any): PartialFunction[Any, KickStartCommand] = {
      pf.andThen { props ⇒
        val commander = sender
        KickStartCommand(cmd, commander, props)
      }
    }

    def kickStartReceive(pf: PartialFunction[Any, Props]): Receive = {
      //case KickStartFailed =
      case cmd if pf.isDefinedAt(cmd) ⇒ selectorPool ! createKickStart(pf, cmd)(cmd)
    }
  }

}