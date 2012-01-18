/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import akka.actor.ActorRef
import akka.dispatch.Future

object PipeToSupport {

  class PipeableFuture[T](val future: Future[T]) {
    def pipeTo(actorRef: ActorRef): Future[T] = akka.pattern.pipeTo(future, actorRef)
  }

}