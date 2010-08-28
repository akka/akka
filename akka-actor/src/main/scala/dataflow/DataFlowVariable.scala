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
  object Start
  object Exit

  import scala.collection.JavaConversions._

  def thread(body: => Unit): Unit = spawn(body)

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
    private object Get extends DataFlowVariableMessage

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
        case Exit     => self.stop
      }
    }

    private class Out[T <: Any](dataFlow: DataFlowVariable[T]) extends Actor {
      self.timeout = TIME_OUT
      private var readerFuture: Option[CompletableFuture[Any]] = None
      def receive = {
        case Get => dataFlow.value.get match {
          case Some(value) => self reply value
          case None        => readerFuture = self.senderFuture
        }
        case Set(v:T) => readerFuture.map(_ completeWithResult v)
        case Exit     => self.stop
      }
    }

    private[this] val in = actorOf(new In(this)).start

    def <<(ref: DataFlowVariable[T]): Unit = if (this.value.get.isEmpty) in ! Set(ref())

    def <<(value: T): Unit = if (this.value.get.isEmpty) in ! Set(value)

    def apply(): T = {
      value.get getOrElse {
        val out = actorOf(new Out(this)).start
        blockedReaders offer out
        val result = (out !! Get).as[T]
        out ! Exit
        result.getOrElse(throw new DataFlowVariableException(
          "Timed out (after " + TIME_OUT + " milliseconds) while waiting for result"))
      }
    }

    def shutdown = in ! Exit
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  /*FIXME I do not work
    class DataFlowStream[T <: Any] extends Seq[T] {
    private[this] val queue = new LinkedBlockingQueue[DataFlowVariable[T]]

    def <<<(ref: DataFlowVariable[T]): Boolean = queue offer ref

    def <<<(value: T): Boolean = {
      val ref = new DataFlowVariable[T]
      ref << value
      queue offer ref
    }

    def apply(): T = queue.take.apply

    def take: DataFlowVariable[T] = queue.take

    //==== For Seq ====

    def length: Int = queue.size

    def apply(i: Int): T = {
      if (i == 0) apply()
      else throw new UnsupportedOperationException(
        "Access by index other than '0' is not supported by DataFlowStream")
    }

    def iterator: Iterator[T] = new Iterator[T] {
      private val i = queue.iterator
      def hasNext: Boolean = i.hasNext
      def next: T = { val ref = i.next; ref() }
    }

    override def toList: List[T] = queue.toArray.toList.asInstanceOf[List[T]]
  }*/

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  class DataFlowVariableException(msg: String) extends AkkaException(msg)
}
