/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.typed.Behavior;
import akka.actor.typed.receptionist.ServiceKey;

public class RoutersTest {

  public void compileOnlyApiTest() {

    final ServiceKey<String> key = ServiceKey.create(String.class, "key");
    Behavior<String> group = Routers.group(key).withRandomRouting().withRoundRobinRouting();

    Behavior<String> pool =
        Routers.pool(5, () -> Behaviors.<String>empty())
            .withRandomRouting()
            .withRoundRobinRouting();
  }
}
