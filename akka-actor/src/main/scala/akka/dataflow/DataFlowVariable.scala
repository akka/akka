/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dataflow

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue}

import akka.actor.{Actor, ActorRef}
import akka.actor.Actor._
import akka.dispatch.CompletableFuture
import akka.AkkaException
import akka.japi.{ Function, Effect }

/**
 * Implements Oz-style dataflow (single assignment) variables.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object DataFlow {
  object Start
  object Exit

  class DataFlowVariableException(msg: String) extends AkkaException(msg)

  /** Executes the supplied thunk in another thread
   */
  def thread(body: => Unit): Unit = spawn(body)

  /** Executes the supplied Effect in another thread
   * JavaAPI
   */
  def thread(body: Effect): Unit = spawn(body.apply)

  /** Executes the supplied function in another thread
   */
  def thread[A <: AnyRef, R <: AnyRef](body: A => R) =
    actorOf(new ReactiveEventBasedThread(body)).start

  /** Executes the supplied Function in another thread
   * JavaAPI
   */
  def thread[A <: AnyRef, R <: AnyRef](body: Function[A,R]) =
    actorOf(new ReactiveEventBasedThread(body.apply)).start

  private class ReactiveEventBasedThread[A <: AnyRef, T <: AnyRef](body: A => T)
    extends Actor {
    def receive = {
      case Exit    => self.stop
      case message => self.reply(body(message.asInstanceOf[A]))
    }
  }

  private object DataFlowVariable {
    private sealed abstract class DataFlowVariableMessage
    private case class Set[T <: Any](value: T) extends DataFlowVariableMessage
    private object Get extends DataFlowVariableMessage
  }

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  sealed class DataFlowVariable[T <: Any](timeoutMs: Long) {
    import DataFlowVariable._

    def this() = this(1000 * 60)

    private val value = new AtomicReference[Option[T]](None)
    private val blockedReaders = new ConcurrentLinkedQueue[ActorRef]

    private class In[T <: Any](dataFlow: DataFlowVariable[T]) extends Actor {
      self.timeout = timeoutMs
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
      self.timeout = timeoutMs
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

    /** Sets the value of this variable (if unset) with the value of the supplied variable
     */
    def <<(ref: DataFlowVariable[T]) {
      if (this.value.get.isEmpty) in ! Set(ref())
      else throw new DataFlowVariableException(
            "Attempt to change data flow variable (from [" + this.value.get + "] to [" + ref() + "])")
    }

    /** Sets the value of this variable (if unset) with the value of the supplied variable
     * JavaAPI
     */
    def set(ref: DataFlowVariable[T]) { this << ref }

    /** Sets the value of this variable (if unset)
     */
    def <<(value: T) {
      if (this.value.get.isEmpty) in ! Set(value)
      else throw new DataFlowVariableException(
            "Attempt to change data flow variable (from [" + this.value.get + "] to [" + value + "])")
    }

    /** Sets the value of this variable (if unset) with the value of the supplied variable
     * JavaAPI
     */
    def set(value: T) { this << value }

    /** Retrieves the value of variable
     *  throws a DataFlowVariableException if it times out
     */
    def get(): T = this()

    /** Retrieves the value of variable
     *  throws a DataFlowVariableException if it times out
     */
    def apply(): T = {
      value.get getOrElse {
        val out = actorOf(new Out(this)).start

        val result = try {
          blockedReaders offer out
          (out !! Get).as[T]
        } catch {
          case e: Exception =>
            out ! Exit
            throw e
        }

        result.getOrElse(throw new DataFlowVariableException("Timed out (after " + timeoutMs + " milliseconds) while waiting for result"))
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
      val result = ref()
      ref.shutdown
      result
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
}
