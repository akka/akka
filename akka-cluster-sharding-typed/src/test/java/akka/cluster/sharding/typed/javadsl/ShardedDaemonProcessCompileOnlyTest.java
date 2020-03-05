/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings;

import java.util.Optional;

public class ShardedDaemonProcessCompileOnlyTest {

  interface Command {}

  enum Stop implements Command {
    INSTANCE
  }

  {
    ActorSystem<Void> system = null;
    ShardedDaemonProcess.get(system).init(Command.class, "MyName", 8, id -> Behaviors.empty());

    ShardedDaemonProcess.get(system)
        .init(
            Command.class,
            "MyName",
            8,
            id -> Behaviors.empty(),
            ShardedDaemonProcessSettings.create(system),
            Optional.of(Stop.INSTANCE));
  }
}
