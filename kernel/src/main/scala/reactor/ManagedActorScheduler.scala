/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import kernel.util.Logging
import net.lag.logging.Logger

import java.util.concurrent.Executors

import scala.actors.{FJTaskScheduler2, Scheduler, IScheduler, Actor}

// FIXME: add managing interface to this class using JMX
// FIXME: configure one instance per GenericServer

class ManagedActorScheduler extends IScheduler with Logging {
  protected var threadPoolSize = 10
  protected val threadPool = Executors.newFixedThreadPool(threadPoolSize)

  def execute(fun: => Unit): Unit = threadPool.execute(new Runnable {
    def run = {
      try {
        fun
      } catch {
        case e => log.error("Actor scheduler", e)
      }
    }
  })

  def execute(task: Runnable) = threadPool.execute(new Runnable {
    def run = {
      try {
        task.run
      } catch {
        case e => log.error("Actor scheduler", e)
      }
    }
  })

  def tick(a: Actor): Unit = {}

  def shutdown: Unit = threadPool.shutdown

  def onLockup(handler: () => Unit): Unit = {}

  def onLockup(millis: Int)(handler: () => Unit): Unit = {}

  def printActorDump: Unit = {}
}

object GlobalManagedActorScheduler extends Logging {
  @volatile private var isRegistered = false
  private var threadPoolSize = 10

  def register = synchronized {
    if (!isRegistered) {
      Scheduler.impl match {
        case fj: FJTaskScheduler2 =>
          fj.snapshot
          fj.shutdown
        case _ =>
      }
      Scheduler.impl = new ManagedActorScheduler
      isRegistered = true
    }
  }
}