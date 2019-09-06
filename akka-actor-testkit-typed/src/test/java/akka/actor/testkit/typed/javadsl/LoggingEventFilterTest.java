/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.javadsl;

import akka.actor.testkit.typed.LoggingEvent;
import akka.actor.testkit.typed.TestException;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoggingEventFilterTest extends JUnitSuite {

  @ClassRule public static TestKitJunitResource testKit = new TestKitJunitResource();

  @Rule public final LogCapturing logCapturing = new LogCapturing();

  private LoggingEvent errorNoCause() {
    return LoggingEvent.create(
        Level.ERROR,
        getClass().getName(),
        Thread.currentThread().getName(),
        "this is an error",
        System.currentTimeMillis(),
        Optional.empty(),
        Optional.empty(),
        Collections.emptyMap());
  }

  private LoggingEvent errorWithCause(Throwable cause) {
    return LoggingEvent.create(
        Level.ERROR,
        getClass().getName(),
        Thread.currentThread().getName(),
        "this is an error",
        System.currentTimeMillis(),
        Optional.empty(),
        Optional.of(cause),
        Collections.emptyMap());
  }

  @Test
  public void filterErrorsWithMatchingMessage() {
    assertTrue(
        LoggingEventFilter.error("an error").matches(errorWithCause(new TestException("exc"))));
    assertTrue(LoggingEventFilter.error("an error").matches(errorNoCause()));
    assertFalse(LoggingEventFilter.error("another error").matches(errorNoCause()));
  }

  @Test
  public void filterErrorsWithMatchingCause() {
    assertTrue(
        LoggingEventFilter.error(TestException.class)
            .matches(errorWithCause(new TestException("exc"))));
    assertFalse(
        LoggingEventFilter.error(TestException.class)
            .matches(errorWithCause(new RuntimeException("exc"))));
    assertTrue(
        LoggingEventFilter.error("an error")
            .withCause(TestException.class)
            .matches(errorWithCause(new TestException("exc"))));
    assertFalse(
        LoggingEventFilter.error("another error")
            .withCause(TestException.class)
            .matches(errorWithCause(new TestException("exc"))));
  }

  @Test
  public void filterErrorsWithMatchingCustomFunction() {
    assertTrue(LoggingEventFilter.custom(event -> true).matches(errorNoCause()));
    assertFalse(
        LoggingEventFilter.custom(event -> event.getMdc().containsKey("aKey"))
            .matches(errorNoCause()));
  }
}
