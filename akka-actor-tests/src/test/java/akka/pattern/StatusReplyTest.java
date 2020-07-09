/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern;

import akka.actor.Actor;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import akka.testkit.TestException;
import akka.testkit.TestProbe;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static akka.pattern.Patterns.askWithStatus;

import static org.junit.Assert.*;

public class StatusReplyTest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("JavaAPI", AkkaSpec.testConf());

  @Test
  public void testSuccessApi() {
    StatusReply<String> reply = StatusReply.success("woho");
    assertTrue(reply.isSuccess());
    assertFalse(reply.isError());
    assertEquals("woho", reply.getValue());
    try {
      reply.getError();
      Assert.fail("Calling get error on success did not throw");
    } catch (IllegalArgumentException ex) {
      // this is what we expect
    }
  }

  @Test
  public void testErrorMessageApi() {
    StatusReply<String> reply = StatusReply.error("boho");
    assertTrue(reply.isError());
    assertFalse(reply.isSuccess());
    assertEquals("boho", reply.getError().getMessage());
    try {
      reply.getValue();
      Assert.fail("Calling get value on error did not throw");
    } catch (StatusReply.ErrorMessage ex) {
      // this is what we expect
    } catch (Throwable th) {
      Assert.fail("Unexpected exception type: " + th);
    }
  }

  @Test
  public void testErrorExceptionApi() {
    StatusReply<String> reply = StatusReply.error(new TestException("boho"));
    assertTrue(reply.isError());
    assertFalse(reply.isSuccess());
    assertEquals("boho", reply.getError().getMessage());
    try {
      reply.getValue();
      Assert.fail("Calling get value on error did not throw");
    } catch (TestException ex) {
      // this is what we expect
    } catch (Throwable th) {
      Assert.fail("Unexpected exception type: " + th);
    }
  }

  @Test
  public void testAskWithStatusSuccess() throws Exception {
    TestProbe probe = new TestProbe(actorSystemResource.getSystem());

    CompletionStage<Object> response = askWithStatus(probe.ref(), "request", Duration.ofSeconds(3));
    probe.expectMsg("request");
    probe.lastSender().tell(StatusReply.success("woho"), Actor.noSender());

    Object result = response.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("woho", result);
  }

  @Test
  public void testAskWithStatusErrorMessage() throws Exception {
    TestProbe probe = new TestProbe(actorSystemResource.getSystem());

    CompletionStage<Object> response = askWithStatus(probe.ref(), "request", Duration.ofSeconds(3));
    probe.expectMsg("request");
    probe.lastSender().tell(StatusReply.error("boho"), Actor.noSender());

    try {
      Object result = response.toCompletableFuture().get(3, TimeUnit.SECONDS);
    } catch (ExecutionException ex) {
      // what we expected
      assertEquals(StatusReply.ErrorMessage.class, ex.getCause().getClass());
      assertEquals("boho", ex.getCause().getMessage());
    }
  }

  @Test
  public void testAskWithStatusErrorException() throws Exception {
    TestProbe probe = new TestProbe(actorSystemResource.getSystem());

    CompletionStage<Object> response = askWithStatus(probe.ref(), "request", Duration.ofSeconds(3));
    probe.expectMsg("request");
    probe.lastSender().tell(StatusReply.error(new TestException("boho")), Actor.noSender());

    try {
      Object result = response.toCompletableFuture().get(3, TimeUnit.SECONDS);
    } catch (ExecutionException ex) {
      // what we expected
      assertEquals(TestException.class, ex.getCause().getClass());
      assertEquals("boho", ex.getCause().getMessage());
    }
  }
}
