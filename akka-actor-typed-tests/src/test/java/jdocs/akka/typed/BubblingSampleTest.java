/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.internal.adapter.ActorSystemAdapter;
import akka.testkit.javadsl.EventFilter;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.TimeUnit;

public class BubblingSampleTest extends JUnitSuite {

  @Test
  public void testBubblingSample() throws Exception {

    final ActorSystem<BubblingSample.Message> system =
        ActorSystem.create(
            BubblingSample.bossBehavior,
            "boss",
            ConfigFactory.parseString(
                "akka.loggers = [ akka.testkit.TestEventListener ]\n" + "akka.loglevel=warning"));

    // actual exception and then the deathpacts
    new EventFilter(Exception.class, ActorSystemAdapter.toUntyped(system))
        .occurrences(4)
        .intercept(
            () -> {
              system.tell(new BubblingSample.Fail("boom"));
              return null;
            });

    system.getWhenTerminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }
}
