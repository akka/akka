/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import scala.util.control.NonFatal

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Outcome
import org.scalatest.TestSuite
import org.slf4j.LoggerFactory

/**
 * Mixin this trait to a test to make log lines appear only when the test failed.
 * Requires Logback and configuration of [[CapturingAppender]] in logback-test.xml.
 */
trait WithLogCapturing extends BeforeAndAfterAll { self: TestSuite =>

  // eager access of CapturingAppender to fail fast if misconfigured
  private val capturingAppender = CapturingAppender.get("")

  private val myLogger = LoggerFactory.getLogger(classOf[WithLogCapturing])

  override protected def afterAll(): Unit = {
    try {
      super.afterAll()
    } catch {
      case NonFatal(e) =>
        myLogger.error("Exception from afterAll", e)
        capturingAppender.flush()
    } finally {
      capturingAppender.clear()
    }
  }

  abstract override def withFixture(test: NoArgTest): Outcome = {
    myLogger.info(s"Logging started for test [${self.getClass.getName}: ${test.name}]")
    val res = test()
    myLogger.info(s"Logging finished for test [${self.getClass.getName}: ${test.name}] that [$res]")

    if (!(res.isSucceeded || res.isPending)) {
      println(
        s"--> [${Console.BLUE}${self.getClass.getName}: ${test.name}${Console.RESET}] Start of log messages of test that [$res]")
      capturingAppender.flush()
      println(
        s"<-- [${Console.BLUE}${self.getClass.getName}: ${test.name}${Console.RESET}] End of log messages of test that [$res]")
    }

    res
  }
  // FIXME #26537 silence stdout debug logging when ActorSystem is started and shutdown

  // FIXME #26537 silence slf4j initialization output (must be some config?)
}
