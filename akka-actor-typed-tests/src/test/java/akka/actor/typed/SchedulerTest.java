/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed;
/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

import java.time.Duration;

public class SchedulerTest {

  public void compileOnly() {
    // accepts a lambda
    ActorSystem<Void> system = null;
    system
        .scheduler()
        .scheduleAtFixedRate(
            Duration.ofMillis(10),
            Duration.ofMillis(10),
            () -> system.log().info("Woo!"),
            system.executionContext());
    system
        .scheduler()
        .scheduleOnce(
            Duration.ofMillis(10), () -> system.log().info("Woo!"), system.executionContext());
  }
}
