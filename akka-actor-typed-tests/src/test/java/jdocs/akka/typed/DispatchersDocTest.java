/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
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
          (context, message) -> {

            // #spawn-dispatcher
            context.spawn(yourBehavior, "DefaultDispatcher");
            context.spawn(
                yourBehavior, "ExplicitDefaultDispatcher", DispatcherSelector.defaultDispatcher());
            context.spawn(yourBehavior, "BlockingDispatcher", DispatcherSelector.blocking());
            context.spawn(
                yourBehavior,
                "DispatcherFromConfig",
                DispatcherSelector.fromConfig("your-dispatcher"));
            // #spawn-dispatcher

            return Behaviors.same();
          });
}
