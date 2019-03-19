/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.throttle;

import org.junit.ClassRule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;
import com.typesafe.config.ConfigFactory;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.AbstractActor;
import akka.testkit.AkkaJUnitActorSystemResource;

public class TimerBasedThrottlerTest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource(
          "TimerBasedThrottlerTest", ConfigFactory.parseString("akka.log-dead-letters=off"));

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void demonstrateUsage() {
    // #demo-code
    // A simple actor that prints whatever it receives
    ActorRef printer = system.actorOf(Props.create(Printer.class));
    // The throttler for this example, setting the rate
    ActorRef throttler =
        system.actorOf(
            Props.create(
                TimerBasedThrottler.class,
                new Throttler.Rate(3, Duration.create(1, TimeUnit.SECONDS))));
    // Set the target
    throttler.tell(new Throttler.SetTarget(printer), null);
    // These three messages will be sent to the target immediately
    throttler.tell("1", null);
    throttler.tell("2", null);
    throttler.tell("3", null);
    // These two will wait until a second has passed
    throttler.tell("4", null);
    throttler.tell("5", null);

    // #demo-code

  }

  public // #demo-code
  // A simple actor that prints whatever it receives
  static class Printer extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .matchAny(
              message -> {
                System.out.println(message);
              })
          .build();
    }
  }

  // #demo-code

  static class System {
    static Out out = new Out();

    static class Out {
      void println(Object s) {}
    }
  }
}
