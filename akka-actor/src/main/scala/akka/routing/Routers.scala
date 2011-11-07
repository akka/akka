/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.routing

import akka.actor.{ UntypedActor, Actor, ActorRef, ForwardableChannel }

/**
 * A Dispatcher is a trait whose purpose is to route incoming messages to actors.
 */
trait Dispatcher { this: Actor ⇒

  protected def transform(msg: Any): Any = msg

  protected def routes: PartialFunction[Any, ActorRef]

  protected def broadcast(message: Any) {}

  protected def dispatch: Receive = {
    case Routing.Broadcast(message) ⇒ broadcast(transform(message))
    case a if routes.isDefinedAt(a) ⇒
      if (isSenderDefined) routes(a).forward(transform(a))(someSelf)
      else routes(a).!(transform(a))(None)
  }

  def receive = dispatch

  protected def isSenderDefined = self.channel.isInstanceOf[ForwardableChannel]
}

/**
 * An UntypedDispatcher is an abstract class whose purpose is to route incoming messages to actors.
 */
abstract class UntypedDispatcher extends UntypedActor {
  protected def transform(msg: Any): Any = msg

  protected def route(msg: Any): ActorRef

  protected def broadcast(message: Any) {}

  protected def isSenderDefined = self.channel.isInstanceOf[ForwardableChannel]

  @throws(classOf[Exception])
  def onReceive(msg: Any): Unit = msg match {
    case Routing.Broadcast(message) ⇒ broadcast(transform(message))
    case msg =>
      val r = route(msg)
      if (r eq null) throw new IllegalStateException("No route for " + msg + " defined!")
      if (isSenderDefined) r.forward(transform(msg))(someSelf)
      else r.!(transform(msg))(None)
  }
}

/**
 * A LoadBalancer is a specialized kind of Dispatcher, that is supplied an InfiniteIterator of targets
 * to dispatch incoming messages to.
 */
trait LoadBalancer extends Dispatcher { self: Actor ⇒
  protected def seq: InfiniteIterator[ActorRef]

  protected def routes = { case x if seq.hasNext ⇒ seq.next }

  override def broadcast(message: Any) =
    seq.items.foreach( a => if (isSenderDefined) a.forward(message)(someSelf) else a.!(message)(None))

  override def isDefinedAt(msg: Any) = seq.exists(_.isDefinedAt(msg))
}

/**
 * A UntypedLoadBalancer is a specialized kind of UntypedDispatcher, that is supplied an InfiniteIterator of targets
 * to dispatch incoming messages to.
 */
abstract class UntypedLoadBalancer extends UntypedDispatcher {
  protected def seq: InfiniteIterator[ActorRef]

  protected def route(msg: Any) = if (seq.hasNext) seq.next else null

  override def broadcast(message: Any) =
    seq.items.foreach( a => if (isSenderDefined) a.forward(message)(someSelf) else a.!(message)(None))

  override def isDefinedAt(msg: Any) = seq.exists(_.isDefinedAt(msg))
}
