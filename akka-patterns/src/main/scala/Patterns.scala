package se.scalablesolutions.akka.actor.patterns

import se.scalablesolutions.akka.actor.Actor

object Patterns {
  type PF[A,B] = PartialFunction[A,B]

  /**
   * Creates a new PartialFunction whose isDefinedAt is a combination
   * of the two parameters, and whose apply is first to call filter.apply and then filtered.apply
   */
  def filter[A,B](filter : PF[A,Unit],filtered : PF[A,B]) : PF[A,B] = {
    case a : A if filtered.isDefinedAt(a) && filter.isDefinedAt(a) =>
      filter(a)
      filtered(a)
  }

  /**
   * Interceptor is a filter(x,y) where x.isDefinedAt is considered to be always true
   */
  def intercept[A,B](interceptor : (A) => Unit, interceptee : PF[A,B]) : PF[A,B] = filter(
      { case a if a.isInstanceOf[A] => interceptor(a) },
      interceptee
    )
  
  //FIXME 2.8, use default params with CyclicIterator
  def loadBalancerActor(actors :  => InfiniteIterator[Actor]) : Actor = new Actor with LoadBalancer {
    val seq = actors
  }

  //FIXME 2.8, use default params with CyclicIterator
  /*def loadBalancerActor(actors : () => List[Actor]) : Actor = loadBalancerActor(
    new CyclicIterator(actors())
  ) */

  def dispatcherActor(routing : PF[Any,Actor], msgTransformer : (Any) => Any) : Actor = new Actor with Dispatcher {
        override def transform(msg : Any) = msgTransformer(msg)
    def routes = routing
  }
  
  def dispatcherActor(routing : PF[Any,Actor]) : Actor = new Actor with Dispatcher {
        def routes = routing
  }

  def loggerActor(actorToLog : Actor, logger : (Any) => Unit) : Actor = dispatcherActor (
    { case _ => actorToLog },
    logger
  )
}

trait Dispatcher { self : Actor =>

  protected def transform(msg : Any) : Any = msg
  protected def routes : PartialFunction[Any,Actor]
  
  protected def dispatch : PartialFunction[Any,Unit] = {
    case a if routes.isDefinedAt(a) => {
      if(self.sender.isDefined)
        routes(a) forward transform(a)
      else
        routes(a) send transform(a)
    }
  }

  def receive = dispatch
}

trait LoadBalancer extends Dispatcher { self : Actor =>
  protected def seq : InfiniteIterator[Actor]

  protected def routes = { case x if seq.hasNext => seq.next }
}

trait InfiniteIterator[T] extends Iterator[T]

class CyclicIterator[T](items : List[T]) extends InfiniteIterator[T] {
  @volatile private[this] var current : List[T] = items
  def hasNext = items != Nil
  def next = {
    val nc = if(current == Nil) items else current
    current = nc.tail
    nc.head
  }
}

//Agent
/*
val a = agent(startValue)
a.set(_ + 5)
a.get
a.foreach println(_)
*/
object Agent {
  sealed trait AgentMessage
  case class FunMessage[T](f : (T) => T) extends AgentMessage
  case class ProcMessage[T](f : (T) => Unit) extends AgentMessage
  case class ValMessage[T](t : T) extends AgentMessage
}
sealed private[akka] class Agent[T] {

}