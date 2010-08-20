/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dataflow

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue}

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.dispatch.CompletableFuture
import se.scalablesolutions.akka.AkkaException

/**
 * Implements Oz-style dataflow (single assignment) variables.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object DataFlow {
  case object Start
  case object Exit

  import java.util.concurrent.atomic.AtomicReference
  import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue}
  import scala.collection.JavaConversions._
  import se.scalablesolutions.akka.actor.Actor
  import se.scalablesolutions.akka.dispatch.CompletableFuture

  def thread(body: => Unit) = {
    val thread = actorOf(new IsolatedEventBasedThread(body)).start
    thread ! Start
    thread
  }

  private class IsolatedEventBasedThread(body: => Unit) extends Actor {
    def receive = {
      case Start => body
      case Exit => self.stop
    }
  }

  def thread[A <: AnyRef, R <: AnyRef](body: A => R) =
    actorOf(new ReactiveEventBasedThread(body)).start

  private class ReactiveEventBasedThread[A <: AnyRef, T <: AnyRef](body: A => T)
    extends Actor {
    def receive = {
      case Exit    => self.stop
      case message => self.reply(body(message.asInstanceOf[A]))
    }
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  sealed class DataFlowVariable[T <: Any] {
    val TIME_OUT = 1000 * 60 // 60 seconds default timeout

    private sealed abstract class DataFlowVariableMessage
    private case class Set[T <: Any](value: T) extends DataFlowVariableMessage
    private case object Get extends DataFlowVariableMessage

    private val value = new AtomicReference[Option[T]](None)
    private val blockedReaders = new ConcurrentLinkedQueue[ActorRef]

    private class In[T <: Any](dataFlow: DataFlowVariable[T]) extends Actor {
      self.timeout = TIME_OUT
      def receive = {
        case s@Set(v) =>
          if (dataFlow.value.compareAndSet(None, Some(v.asInstanceOf[T]))) {
            while(dataFlow.blockedReaders.peek ne null)
             dataFlow.blockedReaders.poll ! s
          } else throw new DataFlowVariableException(
            "Attempt to change data flow variable (from [" + dataFlow.value.get + "] to [" + v + "])")
        case Exit => self.stop
      }
    }

    private class Out[T <: Any](dataFlow: DataFlowVariable[T]) extends Actor {
      self.timeout = TIME_OUT
      private var readerFuture: Option[CompletableFuture[T]] = None
      def receive = {
        case Get =>
          val ref = dataFlow.value.get
          if (ref.isDefined) self.reply(ref.get)
          else readerFuture = self.senderFuture.asInstanceOf[Option[CompletableFuture[T]]]
        case Set(v:T) => if (readerFuture.isDefined) readerFuture.get.completeWithResult(v)
        case Exit => self.stop
      }
    }

    private[this] val in = actorOf(new In(this)).start

    def <<(ref: DataFlowVariable[T]) = in ! Set(ref())

    def <<(value: T) = in ! Set(value)

    def apply(): T = {
      value.get getOrElse {
        val out = actorOf(new Out(this)).start
        blockedReaders.offer(out)
        val result = (out !! Get).as[T]
        out ! Exit
        result.getOrElse(throw new DataFlowVariableException("Timed out (after " + TIME_OUT + " milliseconds) while waiting for result"))
      }
    }

    def shutdown = in ! Exit
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class DataFlowStream[T <: Any] extends Seq[T] {
    private[this] val queue = new LinkedBlockingQueue[DataFlowVariable[T]]

    def <<<(ref: DataFlowVariable[T]) = queue.offer(ref)

    def <<<(value: T) = {
      val ref = new DataFlowVariable[T]
      ref << value
      queue.offer(ref)
    }

    def apply(): T = {
      val ref = queue.take
      ref()
    }

    def take: DataFlowVariable[T] = queue.take

    //==== For Seq ====

    def length: Int = queue.size

    def apply(i: Int): T = {
      if (i == 0) apply()
      else throw new UnsupportedOperationException(
        "Access by index other than '0' is not supported by DataFlowStream")
    }

    def iterator: Iterator[T] = new Iterator[T] {
      private val iter = queue.iterator
      def hasNext: Boolean = iter.hasNext
      def next: T = { val ref = iter.next; ref() }
    }

    override def toList: List[T] = queue.toArray.toList.asInstanceOf[List[T]]
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class DataFlowVariableException(msg: String) extends AkkaException(msg)
}