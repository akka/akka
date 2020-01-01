/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import scala.util.control.NonFatal

import akka.actor.testkit.typed.internal.CapturingAppender
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Outcome
import org.scalatest.TestSuite
import org.slf4j.LoggerFactory

/**
 * Mixin this trait to a ScalaTest test to make log lines appear only when the test failed.
 *
 * Requires Logback and configuration like the following the logback-test.xml:
 *
 * {{{
 *     <appender name="CapturingAppender" class="akka.actor.testkit.typed.internal.CapturingAppender" />
 *
 *     <logger name="akka.actor.testkit.typed.internal.CapturingAppenderDelegate" >
 *       <appender-ref ref="STDOUT"/>
 *     </logger>
 *
 *     <root level="DEBUG">
 *         <appender-ref ref="CapturingAppender"/>
 *     </root>
 * }}}
 */
trait LogCapturing extends BeforeAndAfterAll { self: TestSuite =>

  // eager access of CapturingAppender to fail fast if misconfigured
  private val capturingAppender = CapturingAppender.get("")

  private val myLogger = LoggerFactory.getLogger(classOf[LogCapturing])

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
}
