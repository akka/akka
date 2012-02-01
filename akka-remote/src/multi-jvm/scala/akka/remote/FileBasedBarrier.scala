/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import scala.util.duration._
import scala.util.Duration
import System.{ currentTimeMillis ⇒ now }

import java.io.File

class BarrierTimeoutException(message: String) extends RuntimeException(message)

object FileBasedBarrier {
  val HomeDir = ".multi-jvm"
  val DefaultTimeout = 30.seconds
  val DefaultSleep = 100.millis
}

import FileBasedBarrier._

class FileBasedBarrier(
  name: String,
  count: Int,
  group: String,
  node: String,
  timeout: Duration = FileBasedBarrier.DefaultTimeout,
  sleep: Duration = FileBasedBarrier.DefaultSleep) extends Barrier {

  val barrierDir = {
    val dir = new File(new File(new File(FileBasedBarrier.HomeDir), group), name)
    dir.mkdirs()
    dir
  }

  val nodeFile = new File(barrierDir, node)

  val readyFile = new File(barrierDir, "ready")

  def enter() = {
    createNode()
    if (nodesPresent >= count) createReady()
    val ready = waitFor(readyFile.exists, timeout, sleep)
    if (!ready) expire("entry")
  }

  def leave() = {
    removeNode()
    val empty = waitFor(nodesPresent <= 1, timeout, sleep)
    removeReady()
    if (!empty) expire("exit")
  }

  def nodesPresent = barrierDir.list.size

  def createNode() = nodeFile.createNewFile()

  def removeNode() = nodeFile.delete()

  def createReady() = readyFile.createNewFile()

  def removeReady() = readyFile.delete()

  def waitFor(test: ⇒ Boolean, timeout: Duration, sleep: Duration): Boolean = {
    val start = now
    val limit = start + timeout.toMillis
    var passed = test
    var expired = false
    while (!passed && !expired) {
      if (now > limit) expired = true
      else {
        Thread.sleep(sleep.toMillis)
        passed = test
      }
    }
    passed
  }

  def expire(barrier: String) = {
    throw new BarrierTimeoutException("Timeout (%s) waiting for %s barrier" format (timeout, barrier))
  }
}
