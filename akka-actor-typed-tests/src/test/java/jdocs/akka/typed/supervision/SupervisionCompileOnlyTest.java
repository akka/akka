/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.supervision;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class SupervisionCompileOnlyTest {
  //#wrap
  interface CounterMessage { }

  public static final class Increase implements CounterMessage { }

  public static final class Get implements CounterMessage {
    final ActorRef<Got> sender;

    public Get(ActorRef<Got> sender) {
      this.sender = sender;
    }
  }

  public static final class Got {
    final int n;

    public Got(int n) {
      this.n = n;
    }
  }

  public static Behavior<CounterMessage> counter(int currentValue) {
    return Behaviors.receive(CounterMessage.class)
      .onMessage(Increase.class, (context, o) -> {
        return counter(currentValue + 1);
      })
      .onMessage(Get.class, (context, o) -> {
        o.sender.tell(new Got(currentValue));
        return Behaviors.same();
      })
      .build();
  }
  //#wrap

  public static Behavior<String> behavior = Behaviors.empty();

  public void supervision() {
    //#restart
    Behaviors.supervise(behavior)
      .onFailure(IllegalStateException.class, SupervisorStrategy.restart());
    //#restart

    //#resume
    Behaviors.supervise(behavior)
      .onFailure(IllegalStateException.class, SupervisorStrategy.resume());
    //#resume

    //#restart-limit
    Behaviors.supervise(behavior)
      .onFailure(IllegalStateException.class, SupervisorStrategy.restartWithLimit(
        10, FiniteDuration.apply(10, TimeUnit.SECONDS)
      ));
    //#restart-limit

    //#multiple
    Behaviors.supervise(Behaviors.supervise(behavior)
      .onFailure(IllegalStateException.class, SupervisorStrategy.restart()))
      .onFailure(IllegalArgumentException.class, SupervisorStrategy.stop());
    //#multiple


    //#top-level
    Behaviors.supervise(counter(1));
    //#top-level
  }
}
