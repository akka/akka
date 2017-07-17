/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */

package jdocs.actor;

//#timers
import java.util.concurrent.TimeUnit;
import scala.concurrent.duration.Duration;
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
      getTimers().startSingleTimer(TICK_KEY, new FirstTick(), 
        Duration.create(500, TimeUnit.MILLISECONDS));
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(FirstTick.class, message -> {
          // do something useful here
          getTimers().startPeriodicTimer(TICK_KEY, new Tick(), 
              Duration.create(1, TimeUnit.SECONDS));
        })
        .match(Tick.class, message -> {
            // do something useful here  
        })  
        .build();
    }
  }
  //#timers
}
