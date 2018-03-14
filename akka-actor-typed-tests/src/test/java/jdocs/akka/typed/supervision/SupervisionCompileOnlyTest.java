/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.supervision;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class SupervisionCompileOnlyTest {
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
  }
}
