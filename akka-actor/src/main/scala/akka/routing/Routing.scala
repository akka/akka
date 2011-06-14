/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.routing

import akka.actor.{ UntypedActor, Actor, ActorRef }
import akka.actor.Actor._

import akka.actor.ActorRef
import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import java.util.concurrent.atomic.AtomicReference
import annotation.tailrec

import akka.AkkaException

class RoutingException(message: String) extends AkkaException(message)

sealed trait RouterType

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RouterType {
  object Direct extends RouterType
  object Random extends RouterType
  object RoundRobin extends RouterType
  object LeastCPU extends RouterType
  object LeastRAM extends RouterType
  object LeastMessages extends RouterType
}

/**
 * A Router is a trait whose purpose is to route incoming messages to actors.
 */
trait Router { this: Actor ⇒

  protected def transform(msg: Any): Any = msg

  protected def routes: PartialFunction[Any, ActorRef]

  protected def broadcast(message: Any) {}

  protected def dispatch: Receive = {
    case Routing.Broadcast(message) ⇒
      broadcast(message)
    case a if routes.isDefinedAt(a) ⇒
      if (isSenderDefined) routes(a).forward(transform(a))(someSelf)
      else routes(a).!(transform(a))(None)
  }

  def receive = dispatch

  private def isSenderDefined = self.senderFuture.isDefined || self.sender.isDefined
}

/**
 * An UntypedRouter is an abstract class whose purpose is to route incoming messages to actors.
 */
abstract class UntypedRouter extends UntypedActor {
  protected def transform(msg: Any): Any = msg

  protected def route(msg: Any): ActorRef

  protected def broadcast(message: Any) {}

  private def isSenderDefined = self.senderFuture.isDefined || self.sender.isDefined

  @throws(classOf[Exception])
  def onReceive(msg: Any): Unit = msg match {
    case m: Routing.Broadcast ⇒ broadcast(m.message)
    case _ ⇒
      val r = route(msg)
      if (r eq null) throw new IllegalStateException("No route for " + msg + " defined!")
      if (isSenderDefined) r.forward(transform(msg))(someSelf)
      else r.!(transform(msg))(None)
  }
}

/**
 * A LoadBalancer is a specialized kind of Router, that is supplied an InfiniteIterator of targets
 * to dispatch incoming messages to.
 */
trait LoadBalancer extends Router { self: Actor ⇒
  protected def seq: InfiniteIterator[ActorRef]

  protected def routes = {
    case x if seq.hasNext ⇒ seq.next
  }

  override def broadcast(message: Any) = seq.items.foreach(_ ! message)
}

/**
 * A UntypedLoadBalancer is a specialized kind of UntypedRouter, that is supplied an InfiniteIterator of targets
 * to dispatch incoming messages to.
 */
abstract class UntypedLoadBalancer extends UntypedRouter {
  protected def seq: InfiniteIterator[ActorRef]

  protected def route(msg: Any) =
    if (seq.hasNext) seq.next
    else null

  override def broadcast(message: Any) = seq.items.foreach(_ ! message)
}

object Routing {

  sealed trait RoutingMessage
  case class Broadcast(message: Any) extends RoutingMessage

  type PF[A, B] = PartialFunction[A, B]

  /**
   * Creates a new PartialFunction whose isDefinedAt is a combination
   * of the two parameters, and whose apply is first to call filter.apply
   * and then filtered.apply.
   */
  def filter[A, B](filter: PF[A, Unit], filtered: PF[A, B]): PF[A, B] = {
    case a: A if filtered.isDefinedAt(a) && filter.isDefinedAt(a) ⇒
      filter(a)
      filtered(a)
  }

  /**
   * Interceptor is a filter(x,y) where x.isDefinedAt is considered to be always true.
   */
  def intercept[A: Manifest, B](interceptor: (A) ⇒ Unit, interceptee: PF[A, B]): PF[A, B] =
    filter({ case a ⇒ interceptor(a) }, interceptee)

  /**
   * Creates a LoadBalancer from the thunk-supplied InfiniteIterator.
   */
  def loadBalancerActor(actors: ⇒ InfiniteIterator[ActorRef]): ActorRef =
    actorOf(new Actor with LoadBalancer {
      val seq = actors
    }).start()

  /**
   * Creates a Router given a routing and a message-transforming function.
   */
  def routerActor(routing: PF[Any, ActorRef], msgTransformer: (Any) ⇒ Any): ActorRef =
    actorOf(new Actor with Router {
      override def transform(msg: Any) = msgTransformer(msg)
      def routes = routing
    }).start()

  /**
   * Creates a Router given a routing.
   */
  def routerActor(routing: PF[Any, ActorRef]): ActorRef = actorOf(new Actor with Router {
    def routes = routing
  }).start()

  /**
   * Creates an actor that pipes all incoming messages to
   * both another actor and through the supplied function
   */
  def loggerActor(actorToLog: ActorRef, logger: (Any) ⇒ Unit): ActorRef =
    routerActor({ case _ ⇒ actorToLog }, logger)
}

/**
 * An Iterator that is either always empty or yields an infinite number of Ts.
 */
trait InfiniteIterator[T] extends Iterator[T] {
  val items: Seq[T]
}

/**
 * CyclicIterator is a round-robin style InfiniteIterator that cycles the supplied List.
 */
case class CyclicIterator[T](val items: Seq[T]) extends InfiniteIterator[T] {
  def this(items: java.util.List[T]) = this(items.toList)

  private[this] val current: AtomicReference[Seq[T]] = new AtomicReference(items)

  def hasNext = items != Nil

  def next: T = {
    @tailrec
    def findNext: T = {
      val currentItems = current.get
      val newItems = currentItems match {
        case Nil ⇒ items
        case xs  ⇒ xs
      }

      if (current.compareAndSet(currentItems, newItems.tail)) newItems.head
      else findNext
    }

    findNext
  }

  override def exists(f: T ⇒ Boolean): Boolean = items exists f
}

/**
 * This InfiniteIterator always returns the Actor that has the currently smallest mailbox
 * useful for work-stealing.
 */
case class SmallestMailboxFirstIterator(val items: Seq[ActorRef]) extends InfiniteIterator[ActorRef] {
  def this(items: java.util.List[ActorRef]) = this(items.toList)
  def hasNext = items != Nil

  def next = items.reduceLeft((a1, a2) ⇒ if (a1.dispatcher.mailboxSize(a1) < a2.dispatcher.mailboxSize(a2)) a1 else a2)

  override def exists(f: ActorRef ⇒ Boolean): Boolean = items.exists(f)
}

