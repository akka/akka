/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.pattern;

import akka.actor.*;
import akka.pattern.BackoffSupervisor;
import akka.testkit.TestActors.EchoActor;
//#backoff-imports
import scala.concurrent.duration.Duration;
//#backoff-imports

import java.util.concurrent.TimeUnit;

public class BackoffSupervisorDocTest {

  void example (ActorSystem system) {
    //#backoff
    final Props childProps = Props.create(EchoActor.class);

    final Props  supervisorProps = BackoffSupervisor.props(
      childProps,
      "myEcho",
      Duration.create(3, TimeUnit.SECONDS),
      Duration.create(30, TimeUnit.SECONDS),
      0.2); // adds 20% "noise" to vary the intervals slightly

    system.actorOf(supervisorProps, "echoSupervisor");
    //#backoff
  }

}
