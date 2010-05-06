/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.patterns

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.actor.Actor._

object Patterns {
  type PF[A, B] = PartialFunction[A, B]

  /**
   * Creates a new PartialFunction whose isDefinedAt is a combination
   * of the two parameters, and whose apply is first to call filter.apply and then filtered.apply
   */
  def filter[A, B](filter: PF[A, Unit], filtered: PF[A, B]): PF[A, B] = {
    case a: A if filtered.isDefinedAt(a) && filter.isDefinedAt(a) =>
      filter(a)
      filtered(a)
  }

  /**
   * Interceptor is a filter(x,y) where x.isDefinedAt is considered to be always true
   */
  def intercept[A, B](interceptor: (A) => Unit, interceptee: PF[A, B]): PF[A, B] = 
    filter({case a if a.isInstanceOf[A] => interceptor(a)}, interceptee)

  //FIXME 2.8, use default params with CyclicIterator
  def loadBalancerActor(actors: => InfiniteIterator[ActorRef]): ActorRef = 
    newActor(() => new Actor with LoadBalancer {
      start
      val seq = actors
    })

  def dispatcherActor(routing: PF[Any, ActorRef], msgTransformer: (Any) => Any): ActorRef = 
    newActor(() => new Actor with Dispatcher {
      start
      override def transform(msg: Any) = msgTransformer(msg)
      def routes = routing
    })

  def dispatcherActor(routing: PF[Any, ActorRef]): ActorRef = newActor(() => new Actor with Dispatcher {
    start
    def routes = routing
  })

  def loggerActor(actorToLog: ActorRef, logger: (Any) => Unit): ActorRef = 
    dispatcherActor({case _ => actorToLog}, logger)
}