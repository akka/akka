/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.patterns

import se.scalablesolutions.akka.actor.{Actor, ActorRef}

trait Dispatcher { self: Actor =>

  protected def transform(msg: Any): Any = msg

  protected def routes: PartialFunction[Any, ActorRef]

  protected def dispatch: PartialFunction[Any, Unit] = {
    case a if routes.isDefinedAt(a) =>
      if (self.replyTo.isDefined) routes(a) forward transform(a)
      else routes(a) ! transform(a)
  }

  def receive = dispatch
}

trait LoadBalancer extends Dispatcher { self: Actor =>
  protected def seq: InfiniteIterator[ActorRef]

  protected def routes = { case x if seq.hasNext => seq.next }
}