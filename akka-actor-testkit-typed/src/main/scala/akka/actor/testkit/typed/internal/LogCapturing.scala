/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import scala.util.control.NonFatal

import akka.annotation.InternalApi
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.slf4j.LoggerFactory

/**
 * INTERNAL API
 *
 * JUnit `TestRule` to make log lines appear only when the test failed.
 * Requires Logback and configuration of [[CapturingAppender]] in logback-test.xml.
 *
 * Use this in test by adding a public field annotated with `@TestRule`:
 * {{{
 *   @Rule public final LogCapturing logCapturing = new LogCapturing();
 * }}}
 */
@InternalApi private[akka] class LogCapturing extends TestRule {
  // eager access of CapturingAppender to fail fast if misconfigured
  private val capturingAppender = CapturingAppender.get("")

  private val myLogger = LoggerFactory.getLogger(classOf[WithLogCapturing])

  override def apply(base: Statement, description: Description): Statement = {
    new Statement {
      override def evaluate(): Unit = {
        try {
          myLogger.info(s"Logging started for test [${description.getClassName}: ${description.getMethodName}]")
          base.evaluate()
          myLogger.info(
            s"Logging finished for test [${description.getClassName}: ${description.getMethodName}] that was successful")
        } catch {
          case NonFatal(e) =>
            println(
              s"--> [${Console.BLUE}${description.getClassName}: ${description.getMethodName}${Console.RESET}] " +
              s"Start of log messages of test that failed with ${e.getMessage}")
            capturingAppender.flush()
            println(
              s"<-- [${Console.BLUE}${description.getClassName}: ${description.getMethodName}${Console.RESET}] " +
              s"End of log messages of test that failed with ${e.getMessage}")
            throw e
        } finally {
          capturingAppender.clear()
        }
      }
    }
  }
}
