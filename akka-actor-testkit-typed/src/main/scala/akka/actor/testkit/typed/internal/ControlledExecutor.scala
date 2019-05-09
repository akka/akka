/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import java.util.LinkedList

import akka.annotation.InternalApi

import scala.concurrent.ExecutionContextExecutor

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ControlledExecutor extends ExecutionContextExecutor {
  private val tasks = new LinkedList[Runnable]

  def queueSize: Int = tasks.size()

  def runOne(): Unit = tasks.pop().run()

  def runAll(): Unit = while (!tasks.isEmpty()) runOne()

  def execute(task: Runnable): Unit = {
    tasks.add(task)
  }

  def reportFailure(cause: Throwable): Unit = {
    cause.printStackTrace()
  }
}
