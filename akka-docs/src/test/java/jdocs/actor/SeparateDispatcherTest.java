/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

class SeparateDispatcherTest {
  public static void main(String args[]) {
    Config config = ConfigFactory.parseString(
      "my-blocking-dispatcher {\n" +
        "  type = Dispatcher\n" +
        "  executor = \"thread-pool-executor\"\n" +
        "  thread-pool-executor {\n" +
        "    fixed-pool-size = 16\n" +
        "  }\n" +
        "  throughput = 1\n" +
        "}\n"
    );

    ActorSystem system = ActorSystem.create("BlockingDispatcherTest", config);

    try {
      // #separate-dispatcher-main
      ActorRef actor1 = system.actorOf(Props.create(SeparateDispatcherFutureActor.class));
      ActorRef actor2 = system.actorOf(Props.create(PrintActor.class));

      for (int i = 0; i < 100; i++) {
        actor1.tell(i, ActorRef.noSender());
        actor2.tell(i, ActorRef.noSender());
      }
      // #separate-dispatcher-main
      Thread.sleep(5000 * 6);

    } catch (InterruptedException e) {
      //swallow the exception
    } finally {
      system.terminate();
    }
  }
}
