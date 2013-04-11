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

  trait HasFailureMessage {
    def failureMessage: Any
  }

  abstract class SelectorBasedManager(selectorSettings: SelectionHandlerSettings, nrOfSelectors: Int) extends Actor {

    override def supervisorStrategy = connectionSupervisorStrategy

    val selectorPool = context.actorOf(
      props = Props(new SelectionHandler(self, selectorSettings)).withRouter(RandomRouter(nrOfSelectors)),
      name = "selectors")

    private def createWorkerMessage(pf: PartialFunction[HasFailureMessage, Props]): PartialFunction[HasFailureMessage, WorkerForCommand] = {
      case cmd ⇒
        val props = pf(cmd)
        val commander = sender
        WorkerForCommand(cmd, commander, props)
    }

    def workerForCommandHandler(pf: PartialFunction[Any, Props]): Receive = {
      case cmd: HasFailureMessage if pf.isDefinedAt(cmd) ⇒ selectorPool ! createWorkerMessage(pf)(cmd)
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