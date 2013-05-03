/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import scala.util.control.NonFatal
import akka.actor._
import akka.routing.RandomRouter
import akka.io.SelectionHandler.WorkerForCommand
import akka.event.Logging

object IO {

  trait Extension extends akka.actor.Extension {
    def manager: ActorRef
  }

  def apply[T <: Extension](key: ExtensionKey[T])(implicit system: ActorSystem): ActorRef = key(system).manager

  // What is this? It's public API so I think it deserves a mention
  trait HasFailureMessage {
    def failureMessage: Any
  }

  abstract class SelectorBasedManager(selectorSettings: SelectionHandlerSettings, nrOfSelectors: Int) extends Actor {

    override def supervisorStrategy = connectionSupervisorStrategy

    val selectorPool = context.actorOf(
      props = Props(classOf[SelectionHandler], selectorSettings).withRouter(RandomRouter(nrOfSelectors)),
      name = "selectors")

    def workerForCommandHandler(pf: PartialFunction[HasFailureMessage, Props]): Receive = {
      case cmd: HasFailureMessage if pf.isDefinedAt(cmd) ⇒ selectorPool ! WorkerForCommand(cmd, sender, pf(cmd))
    }
  }

  /**
   * Special supervisor strategy for parents of TCP connection and listener actors.
   * Stops the child on all errors and logs DeathPactExceptions only at debug level.
   */
  private[io] final val connectionSupervisorStrategy: SupervisorStrategy =
    new OneForOneStrategy()(SupervisorStrategy.stoppingStrategy.decider) {
      override protected def logFailure(context: ActorContext, child: ActorRef, cause: Throwable,
                                        decision: SupervisorStrategy.Directive): Unit =
        if (cause.isInstanceOf[DeathPactException]) {
          try context.system.eventStream.publish {
            Logging.Debug(child.path.toString, getClass, "Closed after handler termination")
          } catch { case NonFatal(_) ⇒ }
        } else super.logFailure(context, child, cause, decision)
    }
}