/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern;

import akka.actor.*;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import akka.util.JavaDurationConverters;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.compat.java8.FutureConverters;
import scala.concurrent.Await;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.time.Duration;

import static org.junit.Assert.assertEquals;

public class CircuitBreakerTest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = 
    new AkkaJUnitActorSystemResource("JavaAPI", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void useCircuitBreakerWithCompletableFuture() throws Exception {
    final Duration fiveSeconds = Duration.ofSeconds(5);
    final Duration fiveHundredMillis = Duration.ofMillis(500);
    final CircuitBreaker breaker = new CircuitBreaker(system.dispatcher(), system.scheduler(), 1, fiveSeconds, fiveHundredMillis);

    final CompletableFuture<String> f = new CompletableFuture<>();
    f.complete("hello");
    final CompletionStage<String> res = breaker.callWithCircuitBreakerCS(() -> f);
    assertEquals("hello", Await.result(FutureConverters.toScala(res), JavaDurationConverters.asFiniteDuration(fiveSeconds)));
  }

  @Test
  public void useCircuitBreakerWithCompletableFutureAndCustomDefineFailure() throws Exception {
    final Duration fiveSeconds = Duration.ofSeconds(5);
    final Duration fiveHundredMillis = Duration.ofMillis(500);
    final CircuitBreaker breaker = new CircuitBreaker(system.dispatcher(), system.scheduler(), 1, fiveSeconds, fiveHundredMillis);

    final BiFunction<Optional<String>, Optional<Throwable>, java.lang.Boolean> fn =
            (result, err) -> (result.isPresent() && result.get().equals("hello"));

    final CompletableFuture<String> f = new CompletableFuture<>();
    f.complete("hello");
    final CompletionStage<String> res = breaker.callWithCircuitBreakerCS(() -> f, fn);
    assertEquals("hello", Await.result(FutureConverters.toScala(res), JavaDurationConverters.asFiniteDuration(fiveSeconds)));
    assertEquals(1, breaker.currentFailureCount());
  }
}
