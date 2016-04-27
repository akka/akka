/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import akka.actor.ExtendedActorSystem

import akka.dispatch.AbstractNodeQueue
import akka.event.Logging
import akka.stream.stage.AsyncCallback
import org.agrona.concurrent.BackoffIdleStrategy

object TaskRunner {

  final class Registration(val task: () ⇒ Boolean) {
    private[TaskRunner] var active = false
  }

  sealed trait Command
  case object Shutdown extends Command
  final case class Register(
    registrationCallback: AsyncCallback[Registration],
    task: () ⇒ Boolean) extends Command
  final case class Deregister(registration: Registration) extends Command
  final case class Activate(registration: Registration) extends Command

  final class CommandQueue extends AbstractNodeQueue[Command]
}

class TaskRunner(system: ExtendedActorSystem) extends Runnable {
  import TaskRunner._

  private val log = Logging(system, getClass)
  private[this] var running = false
  private[this] val cmdQueue = new CommandQueue
  private[this] val registrations = new java.util.ArrayList[Registration]()

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
    var i = 0
    val size = registrations.size
    while (i < size) {
      val reg = registrations.get(i)
      if (reg.active) {
        try {
          if (reg.task()) {
            reg.active = false
            reset = true
          }
        } catch {
          case NonFatal(e) ⇒
            log.error(e, "Task failed")
            reg.active = false
            registrations.remove(reg)
        }
      }
      i += 1
    }
  }

  private def processCommand(cmd: Command): Unit = {
    cmd match {
      case null          ⇒ // no command
      case Activate(reg) ⇒ reg.active = true
      case Shutdown      ⇒ running = false
      case Register(callback, task) ⇒
        val reg = new Registration(task)
        registrations.add(reg)
        callback.invoke(reg)

      case Deregister(registration) ⇒
        // FIXME do we need flush?
        registrations.remove(registration)
    }
  }

}
