/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;

public class EventStreamTest {

  static class SomeClass {}

  public static void compileOnlyTest(ActorSystem<?> actorSystem, ActorRef<SomeClass> actorRef) {
    actorSystem.eventStream().tell(Subscribe.of(SomeClass.class, actorRef));
    actorSystem.eventStream().tell(new Subscribe<>(SomeClass.class, actorRef));
  }
}
