/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.testkit;

//#fullsample
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.AbstractActor;
import akka.testkit.JavaTestKit;
import scala.concurrent.duration.Duration;

public class TestKitSampleTest extends AbstractJavaTest {
  
  public static class SomeActor extends AbstractActor {
    ActorRef target = null;

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .matchEquals("hello", message -> {
          sender().tell("world", self());
          if (target != null) target.forward(message, getContext());
        })
        .match(ActorRef.class, actorRef -> {
          target = actorRef;
          sender().tell("done", self());
        })
        .build();
    }
  }
  
  static ActorSystem system;
  
  @BeforeClass
  public static void setup() {
    system = ActorSystem.create();
  }
  
  @AfterClass
  public static void teardown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void testIt() {
    /*
     * Wrap the whole test procedure within a testkit constructor 
     * if you want to receive actor replies or use Within(), etc.
     */
    new JavaTestKit(system) {{
      final Props props = Props.create(SomeActor.class);
      final ActorRef subject = system.actorOf(props);

      // can also use JavaTestKit “from the outside”
      final JavaTestKit probe = new JavaTestKit(system);
      // “inject” the probe by passing it to the test subject
      // like a real resource would be passed in production
      subject.tell(probe.getRef(), getRef());
      // await the correct response
      expectMsgEquals(duration("1 second"), "done");
      
      // the run() method needs to finish within 3 seconds
      new Within(duration("3 seconds")) {
        protected void run() {

          subject.tell("hello", getRef());

          // This is a demo: would normally use expectMsgEquals().
          // Wait time is bounded by 3-second deadline above.
          new AwaitCond() {
            protected boolean cond() {
              return probe.msgAvailable();
            }
          };

          // response must have been enqueued to us before probe
          expectMsgEquals(Duration.Zero(), "world");
          // check that the probe we injected earlier got the msg
          probe.expectMsgEquals(Duration.Zero(), "hello");
          Assert.assertEquals(getRef(), probe.getLastSender());

          // Will wait for the rest of the 3 seconds
          expectNoMsg();
        }
      };
    }};
  }
  
}
//#fullsample
