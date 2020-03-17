/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings;

import java.util.Arrays;
import java.util.List;
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

    // #tag-processing
    List<String> tags = Arrays.asList("tag-1", "tag-2", "tag-3");
    ShardedDaemonProcess.get(system)
        .init(
            TagProcessor.Command.class,
            "TagProcessors",
            tags.size(),
            id -> TagProcessor.create(tags.get(id)));
    // #tag-processing
  }

  static class TagProcessor {
    interface Command {}

    static Behavior<Command> create(String tag) {
      return Behaviors.setup(
          context -> {
            // ... start the tag processing ...
            return Behaviors.empty();
          });
    }
  }
}
