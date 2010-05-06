/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.patterns

import se.scalablesolutions.akka.actor.{Actor, ActorID}
import se.scalablesolutions.akka.actor.Actor._

object Patterns {
  type PF[A, B] = PartialFunction[A, B]

  /** Creates a new PartialFunction whose isDefinedAt is a combination
   * of the two parameters, and whose apply is first to call filter.apply and then filtered.apply
   */
  def filter[A, B](filter: PF[A, Unit], filtered: PF[A, B]): PF[A, B] = {
    case a: A if filtered.isDefinedAt(a) && filter.isDefinedAt(a) =>
      filter(a)
      filtered(a)
  }

  /** Interceptor is a filter(x,y) where x.isDefinedAt is considered to be always true
   */
  def intercept[A, B](interceptor: (A) => Unit, interceptee: PF[A, B]): PF[A, B] = 
    filter({case a if a.isInstanceOf[A] => interceptor(a)}, interceptee)

  /** Creates a LoadBalancer from the thunk-supplied InfiniteIterator
   */
  def loadBalancerActor(actors: => InfiniteIterator[ActorID]): ActorID = 
    newActor(() => new Actor with LoadBalancer {
      start
      val seq = actors
    })

  /** Creates a Dispatcher given a routing and a message-transforming function
   */
  def dispatcherActor(routing: PF[Any, ActorID], msgTransformer: (Any) => Any): ActorID = 
    newActor(() => new Actor with Dispatcher {
      start
      override def transform(msg: Any) = msgTransformer(msg)
      def routes = routing
    })

  /** Creates a Dispatcher given a routing
   */
  def dispatcherActor(routing: PF[Any, ActorID]): ActorID = newActor(() => new Actor with Dispatcher {
    start
    def routes = routing
  })

  /** Creates an actor that pipes all incoming messages to 
   *  both another actor and through the supplied function
   */
  def loggerActor(actorToLog: ActorID, logger: (Any) => Unit): ActorID = 
    dispatcherActor({case _ => actorToLog}, logger)
}