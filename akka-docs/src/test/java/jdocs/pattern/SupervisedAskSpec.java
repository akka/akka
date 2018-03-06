/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.pattern;

import akka.actor.ActorRef;
import akka.actor.ActorRefFactory;
import akka.actor.Props;
import akka.actor.AbstractActor;
import akka.util.Timeout;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.CompletionStage;

public class SupervisedAskSpec {

  public Object execute(Class<? extends AbstractActor> someActor,
      Object message, Timeout timeout, ActorRefFactory actorSystem)
      throws Exception {
    // example usage
    try {
      ActorRef supervisorCreator = SupervisedAsk
          .createSupervisorCreator(actorSystem);
      CompletionStage<Object> finished = SupervisedAsk.askOf(supervisorCreator,
          Props.create(someActor), message, timeout);
      FiniteDuration d = timeout.duration();
      return finished.toCompletableFuture().get(d.length(), d.unit());
    } catch (Exception e) {
      // exception propagated by supervision
      throw e;
    }
  }
}
