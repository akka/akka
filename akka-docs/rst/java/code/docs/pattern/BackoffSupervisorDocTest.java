/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.pattern;

import akka.actor.*;
import akka.pattern.Backoff;
import akka.pattern.BackoffSupervisor;
import akka.testkit.TestActors.EchoActor;
//#backoff-imports
import scala.concurrent.duration.Duration;
//#backoff-imports

import java.util.concurrent.TimeUnit;

public class BackoffSupervisorDocTest {

  void exampleStop (ActorSystem system) {
    //#backoff-stop
    final Props childProps = Props.create(EchoActor.class);

    final Props  supervisorProps = BackoffSupervisor.props(
      Backoff.onStop(
        childProps,
        "myEcho",
        Duration.create(3, TimeUnit.SECONDS),
        Duration.create(30, TimeUnit.SECONDS),
        0.2)); // adds 20% "noise" to vary the intervals slightly

    system.actorOf(supervisorProps, "echoSupervisor");
    //#backoff-stop
  }

  void exampleFailure (ActorSystem system) {
    //#backoff-fail
    final Props childProps = Props.create(EchoActor.class);

    final Props  supervisorProps = BackoffSupervisor.props(
      Backoff.onFailure(
        childProps,
        "myEcho",
        Duration.create(3, TimeUnit.SECONDS),
        Duration.create(30, TimeUnit.SECONDS),
        0.2)); // adds 20% "noise" to vary the intervals slightly

    system.actorOf(supervisorProps, "echoSupervisor");
    //#backoff-fail
  }
}
