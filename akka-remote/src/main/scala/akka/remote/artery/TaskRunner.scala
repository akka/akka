/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal
import akka.actor.ExtendedActorSystem
import akka.dispatch.AbstractNodeQueue
import akka.event.Logging
import org.agrona.concurrent.BackoffIdleStrategy
import scala.annotation.tailrec
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
private[akka] object TaskRunner {

  type Task = () ⇒ Boolean
  sealed trait Command
  case object Shutdown extends Command
  final case class Add(task: Task) extends Command
  final case class Remove(task: Task) extends Command

  final class CommandQueue extends AbstractNodeQueue[Command]

  /**
   * A specialized collection with allocation free add, remove and iterate of
   * elements. The order of the elements is not important.
   */
  private final class ArrayBag[T <: AnyRef: ClassTag] {
    private var elements = Array.ofDim[T](16)

    def add(e: T): Unit = {
      val size = elements.length
      @tailrec def tryAdd(i: Int): Unit = {
        if (i == size) {
          doubleCapacity()
          elements(i) = e
        } else if (elements(i) eq null)
          elements(i) = e
        else
          tryAdd(i + 1) //recursive
      }
      tryAdd(0)
    }

    def remove(e: T): Unit = {
      val size = elements.length
      @tailrec def tryRemove(i: Int): Unit = {
        if (i == size)
          () // not found
        else if (elements(i) == e)
          elements(i) = null.asInstanceOf[T]
        else
          tryRemove(i + 1) //recursive
      }
      tryRemove(0)
    }

    /**
     * All elements as an array for efficient iteration.
     * The elements can be `null`.
     */
    def all: Array[T] = elements

    private def doubleCapacity(): Unit = {
      val newCapacity = elements.length << 1
      if (newCapacity < 0)
        throw new IllegalStateException("Sorry, too big")
      val a = Array.ofDim[T](newCapacity)
      System.arraycopy(elements, 0, a, 0, elements.length)
      elements = a
    }

    override def toString(): String =
      elements.filterNot(_ eq null).mkString("[", ",", "]")
  }
}

/**
 * INTERNAL API
 */
private[akka] class TaskRunner(system: ExtendedActorSystem) extends Runnable {
  import TaskRunner._

  private val log = Logging(system, getClass)
  private[this] var running = false
  private[this] val cmdQueue = new CommandQueue
  private[this] val tasks = new ArrayBag[Task]

  // TODO the backoff strategy should be measured and tuned
  private val spinning = 2000000
  private val yielding = 0
  private val idleStrategy = new BackoffIdleStrategy(
    spinning, yielding, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100))
  private var reset = false

  def start(): Unit = {
    val thread = system.threadFactory.newThread(this)
    thread.start()
  }

  def stop(): Unit = {
    command(Shutdown)
  }

  def command(cmd: Command): Unit = {
    cmdQueue.add(cmd)
  }

  override def run(): Unit = {
    try {
      running = true
      while (running) {
        executeTasks()
        processCommand(cmdQueue.poll())
        if (reset) {
          reset = false
          idleStrategy.reset()
        }
        idleStrategy.idle()
      }
    } catch {
      case NonFatal(e) ⇒
        log.error(e, e.getMessage)
    }
  }

  private def executeTasks(): Unit = {
    val elements = tasks.all
    var i = 0
    val size = elements.length
    while (i < size) {
      val task = elements(i)
      if (task ne null) try {
        if (task()) {
          tasks.remove(task)
          reset = true
        }
      } catch {
        case NonFatal(e) ⇒
          log.error(e, "Task failed")
          tasks.remove(task)
      }
      i += 1
    }
  }

  private def processCommand(cmd: Command): Unit = {
    cmd match {
      case null         ⇒ // no command
      case Add(task)    ⇒ tasks.add(task)
      case Remove(task) ⇒ tasks.remove(task)
      case Shutdown     ⇒ running = false
    }
  }

}
