/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actor;

//#imports1
import akka.actor.Props;
import docs.AbstractJavaTest;
import scala.concurrent.duration.Duration;
import java.util.concurrent.TimeUnit;
//#imports1

//#imports2
import akka.actor.UntypedActor;
import akka.actor.Cancellable;
//#imports2

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.testkit.AkkaSpec;
import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.*;

public class SchedulerDocTest extends AbstractJavaTest {
  
  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("SchedulerDocTest",
      AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();
  private ActorRef testActor = system.actorOf(Props.create(MyUntypedActor.class));

  @Test
  public void scheduleOneOffTask() {
    //#schedule-one-off-message
    system.scheduler().scheduleOnce(Duration.create(50, TimeUnit.MILLISECONDS),
      testActor, "foo", system.dispatcher(), null);
    //#schedule-one-off-message

    //#schedule-one-off-thunk
    system.scheduler().scheduleOnce(Duration.create(50, TimeUnit.MILLISECONDS),
      new Runnable() {
        @Override
        public void run() {
          testActor.tell(System.currentTimeMillis(), ActorRef.noSender());
        }
    }, system.dispatcher());
    //#schedule-one-off-thunk
  }

  @Test
  public void scheduleRecurringTask() {
    //#schedule-recurring
    class Ticker extends UntypedActor {
      @Override
      public void onReceive(Object message) {
        if (message.equals("Tick")) {
          // Do someting
        } else {
          unhandled(message);
        }
      }
    }
    
    ActorRef tickActor = system.actorOf(Props.create(Ticker.class, this));

    //This will schedule to send the Tick-message
    //to the tickActor after 0ms repeating every 50ms
    Cancellable cancellable = system.scheduler().schedule(Duration.Zero(),
    Duration.create(50, TimeUnit.MILLISECONDS), tickActor, "Tick",
    system.dispatcher(), null);

    //This cancels further Ticks to be sent
    cancellable.cancel();
    //#schedule-recurring
    system.stop(tickActor);
  }
}
