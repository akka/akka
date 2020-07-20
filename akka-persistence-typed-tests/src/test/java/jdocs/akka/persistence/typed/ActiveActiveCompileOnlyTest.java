/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.persistence.typed.ReplicaId;
import akka.persistence.typed.javadsl.*;

import java.util.*;

public class ActiveActiveCompileOnlyTest {

  // dummy for docs example
  interface Command {}
  interface Event {}
  interface State {}

  static // #factory
  final class MyActiceActiveEventSourcedBehavior
      extends ActiveActiveEventSourcedBehavior<
          Command,
          Event,
          State> {

    public MyActiceActiveEventSourcedBehavior(ActiveActiveContext activeActiveContext) {
      super(activeActiveContext);
    }
    // ... implementation of abstract methods ...
    // #factory

    @Override
    public State emptyState() {
      return null;
    }

    @Override
    public CommandHandler<Command, Event, State> commandHandler() {
      return null;
    }

    @Override
    public EventHandler<State, Event> eventHandler() {
      return null;
    }
    // #factory
  }

  // #factory

  {
    // #replicas
    ReplicaId DCA = new ReplicaId("DC-A");
    ReplicaId DCB = new ReplicaId("DC-B");
    Set<ReplicaId> allReplicas =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(DCA, DCB)));
    // #replicas

    String queryPluginId = "";

    // #factory-shared
    ActiveActiveEventSourcing.withSharedJournal(
        "entityId",
        DCA,
        allReplicas,
        queryPluginId,
        context -> new MyActiceActiveEventSourcedBehavior(context));
    // #factory-shared

    // #factory

    // bootstrap logic
    Map<ReplicaId, String> allReplicasAndQueryPlugins = new HashMap<>();
    allReplicasAndQueryPlugins.put(DCA, "journalForDCA");
    allReplicasAndQueryPlugins.put(DCB, "journalForDCB");

    EventSourcedBehavior<Command, Event, State> behavior = ActiveActiveEventSourcing.create(
            "entityId",
            DCA,
            allReplicasAndQueryPlugins,
            context -> new MyActiceActiveEventSourcedBehavior(context));
    // #factory
  }
}
