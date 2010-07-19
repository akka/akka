/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.routing

import se.scalablesolutions.akka.actor.{Actor, ActorRef}

/**
 * A Dispatcher is a trait whose purpose is to route incoming messages to actors.
 */
trait Dispatcher { this: Actor =>

  protected def transform(msg: Any): Any = msg

  protected def routes: PartialFunction[Any, ActorRef]

  protected def dispatch: Receive = {
    case a if routes.isDefinedAt(a) =>
      if (isSenderDefined) routes(a).forward(transform(a))(someSelf)
      else routes(a).!(transform(a))(None)
  }

  def receive = dispatch

  private def isSenderDefined = self.senderFuture.isDefined || self.sender.isDefined
}

/**
 * A LoadBalancer is a specialized kind of Dispatcher, that is supplied an InfiniteIterator of targets
 * to dispatch incoming messages to.
 */
trait LoadBalancer extends Dispatcher { self: Actor =>
  protected def seq: InfiniteIterator[ActorRef]

  protected def routes = { case x if seq.hasNext => seq.next }

  override def isDefinedAt(msg: Any) = seq.exists( _.isDefinedAt(msg) )
}
