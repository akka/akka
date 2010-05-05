/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.patterns

import se.scalablesolutions.akka.actor.Actor

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

  def loadBalancerActor(actors: => InfiniteIterator[Actor]): Actor = new Actor with LoadBalancer {
    val seq = actors
  }

  def dispatcherActor(routing: PF[Any, Actor], msgTransformer: (Any) => Any): Actor = 
    new Actor with Dispatcher {
    override def transform(msg: Any) = msgTransformer(msg)
    def routes = routing
  }

  def dispatcherActor(routing: PF[Any, Actor]): Actor = new Actor with Dispatcher {
    def routes = routing
  }

  def loggerActor(actorToLog: Actor, logger: (Any) => Unit): Actor = 
    dispatcherActor({case _ => actorToLog}, logger)
}