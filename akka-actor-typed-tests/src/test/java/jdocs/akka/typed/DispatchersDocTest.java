/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import org.scalatest.junit.JUnitSuite;
import akka.actor.typed.DispatcherSelector;

public class DispatchersDocTest {

  private static Behavior<String> yourBehavior = Behaviors.empty();

  private static Behavior<Object> example =
      Behaviors.receive(
          (ctx, msg) -> {

            // #spawn-dispatcher
            ctx.spawn(yourBehavior, "DefaultDispatcher");
            ctx.spawn(
                yourBehavior, "ExplicitDefaultDispatcher", DispatcherSelector.defaultDispatcher());
            ctx.spawn(yourBehavior, "BlockingDispatcher", DispatcherSelector.blocking());
            ctx.spawn(
                yourBehavior,
                "DispatcherFromConfig",
                DispatcherSelector.fromConfig("your-dispatcher"));
            // #spawn-dispatcher

            return Behaviors.same();
          });
}
