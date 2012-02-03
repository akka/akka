/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import akka.dispatch.Future
import akka.dispatch.OldFuture
import scala.util.Timeout
import akka.actor.GlobalActorSystem
import akka.dispatch.MessageDispatcher
import akka.actor.ActorRef

package object migration {

  implicit def future2OldFuture[T](future: Future[T]): OldFuture[T] = new OldFuture[T](future)

  implicit def askTimeout: Timeout = GlobalActorSystem.settings.ActorTimeout

  implicit def defaultDispatcher: MessageDispatcher = GlobalActorSystem.dispatcher

  implicit def actorRef2OldActorRef(actorRef: ActorRef) = new OldActorRef(actorRef)

  class OldActorRef(actorRef: ActorRef) {
    @deprecated("Actors are automatically started when created, i.e. remove old call to start()", "2.0")
    def start(): ActorRef = actorRef

    @deprecated("Stop with ActorSystem or ActorContext instead", "2.0")
    def exit() = stop()

    @deprecated("Stop with ActorSystem or ActorContext instead", "2.0")
    def stop(): Unit = GlobalActorSystem.stop(actorRef)
  }

  implicit def ask(actorRef: ActorRef) = new akka.migration.AskableActorRef(actorRef)
  def ask(actorRef: ActorRef, message: Any)(implicit timeout: Timeout = null): Future[Any] = akka.pattern.ask(actorRef, message)(timeout)

}