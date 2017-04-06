/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.internal

import scala.concurrent.ExecutionContextExecutor
import java.util.LinkedList

class ControlledExecutor extends ExecutionContextExecutor {
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
