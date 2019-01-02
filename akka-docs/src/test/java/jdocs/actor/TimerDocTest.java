/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

//#timers
import java.time.Duration;
import akka.actor.AbstractActorWithTimers;

//#timers

public class TimerDocTest {

  static
  //#timers
  public class MyActor extends AbstractActorWithTimers {
    
    private static Object TICK_KEY = "TickKey";
    private static final class FirstTick {
    }
    private static final class Tick {
    }

    public MyActor() {
      getTimers().startSingleTimer(TICK_KEY, new FirstTick(), Duration.ofMillis(500));
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(FirstTick.class, message -> {
          // do something useful here
          getTimers().startPeriodicTimer(TICK_KEY, new Tick(), Duration.ofSeconds(1));
        })
        .match(Tick.class, message -> {
            // do something useful here  
        })  
        .build();
    }
  }
  //#timers
}
