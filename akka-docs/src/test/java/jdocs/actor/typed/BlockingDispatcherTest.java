/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor.typed;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

public class BlockingDispatcherTest {
  public static void main(String args[]) {
    // #blocking-main
    Behavior<Void> root =
        Behaviors.setup(
            context -> {
              ActorRef<Integer> actor1 =
                  context.spawn(BlockingFutureActor.create(), "BlockingFutureActor");
              ActorRef<Integer> actor2 = context.spawn(new PrintActor(), "PrintActor");

              for (int i = 0; i < 100; i++) {
                actor1.tell(i);
                actor2.tell(i);
              }
              return Behaviors.ignore();
            });
    // #blocking-main

    ActorSystem<Void> system = ActorSystem.<Void>create(root, "BlockingDispatcherTest");
  }
}
