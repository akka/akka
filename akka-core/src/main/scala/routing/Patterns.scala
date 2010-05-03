package se.scalablesolutions.akka.patterns

import se.scalablesolutions.akka.actor.{Actor, ActorID}
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
  def loadBalancerActor(actors: => InfiniteIterator[ActorID]): ActorID = newActor(() => new Actor with LoadBalancer {
    start
    val seq = actors
  })

  def dispatcherActor(routing: PF[Any, ActorID], msgTransformer: (Any) => Any): ActorID = newActor(() => new Actor with Dispatcher {
    start
    override def transform(msg: Any) = msgTransformer(msg)
    def routes = routing
  })

  def dispatcherActor(routing: PF[Any, ActorID]): ActorID = newActor(() => new Actor with Dispatcher {
    start
    def routes = routing
  })

  def loggerActor(actorToLog: ActorID, logger: (Any) => Unit): ActorID = 
    dispatcherActor({case _ => actorToLog}, logger)
}

trait Dispatcher { self: Actor =>

  protected def transform(msg: Any): Any = msg

  protected def routes: PartialFunction[Any, ActorID]

  protected def dispatch: PartialFunction[Any, Unit] = {
    case a if routes.isDefinedAt(a) =>
      if (self.replyTo.isDefined) routes(a) forward transform(a)
      else routes(a) ! transform(a)
  }

  def receive = dispatch
}

trait LoadBalancer extends Dispatcher { self: Actor =>
  protected def seq: InfiniteIterator[ActorID]

  protected def routes = { case x if seq.hasNext => seq.next }
}

trait InfiniteIterator[T] extends Iterator[T]

class CyclicIterator[T](items: List[T]) extends InfiniteIterator[T] {
  @volatile private[this] var current: List[T] = items

  def hasNext = items != Nil

  def next = {
    val nc = if (current == Nil) items else current
    current = nc.tail
    nc.head
  }
}

class SmallestMailboxFirstIterator(items : List[ActorID]) extends InfiniteIterator[ActorID] {
  def hasNext = items != Nil

  def next = {
    def actorWithSmallestMailbox(a1: ActorID, a2: ActorID) = {
      if (a1.mailboxSize < a2.mailboxSize) a1 else a2
    }
    items.reduceLeft((actor1, actor2) => actorWithSmallestMailbox(actor1,actor2))
  }
} 

sealed trait ListenerMessage
case class Listen(listener : ActorID) extends ListenerMessage
case class Deafen(listener : ActorID) extends ListenerMessage 
case class WithListeners(f : Set[ActorID] => Unit) extends ListenerMessage

trait Listeners { self : Actor =>
  import se.scalablesolutions.akka.actor.Agent
  private lazy val listeners = Agent(Set[ActorID]())

  protected def listenerManagement : PartialFunction[Any,Unit] = {
    case Listen(l) => listeners( _ + l)
    case Deafen(l) => listeners( _ - l )
    case WithListeners(f) => listeners foreach f
  }
  
  protected def gossip(msg : Any) = listeners foreach ( _ foreach ( _ ! msg ) )
}