/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#imports1
import akka.actor.Props;
import scala.concurrent.util.Duration;
import java.util.concurrent.TimeUnit;

//#imports1

//#imports2
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.actor.Cancellable;

//#imports2

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.testkit.AkkaSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SchedulerDocTestBase {

  ActorSystem system;
  ActorRef testActor;

  @Before
  public void setUp() {
    system = ActorSystem.create("MySystem", AkkaSpec.testConf());
    testActor = system.actorOf(new Props(MyUntypedActor.class));
  }

  @After
  public void tearDown() {
    system.shutdown();
  }

  @Test
  public void scheduleOneOffTask() {
    //#schedule-one-off-message
    //Schedules to send the "foo"-message to the testActor after 50ms
    system.scheduler().scheduleOnce(Duration.create(50, TimeUnit.MILLISECONDS), testActor, "foo", system.dispatcher());
    //#schedule-one-off-message

    //#schedule-one-off-thunk
    //Schedules a Runnable to be executed (send the current time) to the testActor after 50ms
    system.scheduler().scheduleOnce(Duration.create(50, TimeUnit.MILLISECONDS), new Runnable() {
      @Override
      public void run() {
        testActor.tell(System.currentTimeMillis(), null);
      }
    }, system.dispatcher());
    //#schedule-one-off-thunk
  }

  @Test
  public void scheduleRecurringTask() {
    //#schedule-recurring
    ActorRef tickActor = system.actorOf(new Props().withCreator(new UntypedActorFactory() {
      public UntypedActor create() {
        return new UntypedActor() {
          public void onReceive(Object message) {
            if (message.equals("Tick")) {
              // Do someting
            } else {
              unhandled(message);
            }
          }
        };
      }
    }));

    //This will schedule to send the Tick-message
    //to the tickActor after 0ms repeating every 50ms
    Cancellable cancellable = system.scheduler().schedule(Duration.Zero(), Duration.create(50, TimeUnit.MILLISECONDS),
        tickActor, "Tick", system.dispatcher());

    //This cancels further Ticks to be sent
    cancellable.cancel();
    //#schedule-recurring
    system.stop(tickActor);
  }
}
