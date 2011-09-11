/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.util.duration._
import akka.util.Duration
import System.{ currentTimeMillis ⇒ now }

import java.io.File

trait ClusterTestNode extends WordSpec with MustMatchers with BeforeAndAfterAll {

  override def beforeAll() = {
    ClusterTestNode.waitForReady(getClass.getName)
  }

  override def afterAll() = {
    ClusterTestNode.exit(getClass.getName)
  }
}

object ClusterTestNode {
  val TestMarker = "MultiJvm"
  val HomeDir = "_akka_cluster"
  val TestDir = "multi-jvm"
  val Sleep = 100.millis
  val Timeout = 1.minute

  def ready(className: String) = {
    readyFile(className).createNewFile()
  }

  def waitForReady(className: String) = {
    if (!waitExists(readyFile(className))) {
      cleanUp(className)
      sys.error("Timeout waiting for cluster ready")
    }
  }

  def exit(className: String) = {
    exitFile(className).createNewFile()
  }

  def waitForExits(className: String, nodes: Int) = {
    if (!waitCount(exitDir(className), nodes)) {
      cleanUp(className)
      sys.error("Timeout waiting for node exits")
    }
  }

  def cleanUp(className: String) = {
    deleteRecursive(testDir(className))
  }

  def testName(name: String) = {
    val i = name.indexOf(TestMarker)
    if (i >= 0) name.substring(0, i) else name
  }

  def nodeName(name: String) = {
    val i = name.indexOf(TestMarker)
    if (i >= 0) name.substring(i + TestMarker.length) else name
  }

  def testDir(className: String) = {
    val home = new File(HomeDir)
    val tests = new File(home, TestDir)
    val dir = new File(tests, testName(className))
    dir.mkdirs()
    dir
  }

  def readyFile(className: String) = {
    new File(testDir(className), "ready")
  }

  def exitDir(className: String) = {
    val dir = new File(testDir(className), "exit")
    dir.mkdirs()
    dir
  }

  def exitFile(className: String) = {
    new File(exitDir(className), nodeName(className))
  }

  def waitExists(file: File) = waitFor(file.exists)

  def waitCount(file: File, n: Int) = waitFor(file.list.size >= n)

  def waitFor(test: ⇒ Boolean, sleep: Duration = Sleep, timeout: Duration = Timeout): Boolean = {
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

  def deleteRecursive(file: File): Boolean = {
    if (file.isDirectory) file.listFiles.foreach(deleteRecursive)
    file.delete()
  }
}
