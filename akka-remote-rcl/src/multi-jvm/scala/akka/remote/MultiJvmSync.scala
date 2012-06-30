/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.testkit.AkkaSpec
import akka.util.Duration

trait MultiJvmSync extends AkkaSpec {
  def nodes: Int

  override def atStartup() = {
    onStart()
    MultiJvmSync.start(getClass.getName, nodes)
  }

  def onStart() {}

  override def atTermination() = {
    MultiJvmSync.end(getClass.getName, nodes)
    onEnd()
  }

  def onEnd() {}

  def barrier(name: String, timeout: Duration = FileBasedBarrier.DefaultTimeout) = {
    MultiJvmSync.barrier(name, nodes, getClass.getName, timeout)
  }
}

object MultiJvmSync {
  val TestMarker = "MultiJvm"
  val StartBarrier = "multi-jvm-start"
  val EndBarrier = "multi-jvm-end"

  def start(className: String, count: Int) = barrier(StartBarrier, count, className)

  def end(className: String, count: Int) = barrier(EndBarrier, count, className)

  def barrier(name: String, count: Int, className: String, timeout: Duration = FileBasedBarrier.DefaultTimeout) = {
    val Array(testName, nodeName) = className split TestMarker
    val barrier = new FileBasedBarrier(name, count, testName, nodeName, timeout)
    barrier.await()
  }
}
