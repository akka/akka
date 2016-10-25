/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.dispatcher;

import akka.dispatch.ControlMessage;
import akka.dispatch.RequiresMessageQueue;
import akka.testkit.AkkaSpec;
import com.typesafe.config.ConfigFactory;
import docs.AbstractJavaTest;
import docs.actor.MyBoundedUntypedActor;
import docs.actor.MyUntypedActor;
import org.junit.ClassRule;
import org.junit.Test;
import scala.concurrent.ExecutionContext;

//#imports
import akka.actor.*;
//#imports

//#imports-prio
import akka.event.Logging;
import akka.event.LoggingAdapter;

//#imports-prio

//#imports-prio-mailbox
import akka.dispatch.PriorityGenerator;
import akka.dispatch.UnboundedStablePriorityMailbox;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.JavaTestKit;
import com.typesafe.config.Config;

//#imports-prio-mailbox

//#imports-required-mailbox

//#imports-required-mailbox

public class DispatcherDocTest extends AbstractJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("DispatcherDocTest", ConfigFactory.parseString(
      DispatcherDocSpec.javaConfig()).withFallback(ConfigFactory.parseString(
      DispatcherDocSpec.config())).withFallback(AkkaSpec.testConf()));

  private final ActorSystem system = actorSystemResource.getSystem();

  @SuppressWarnings("unused")
  @Test
  public void defineDispatcherInConfig() {
    //#defining-dispatcher-in-config
    ActorRef myActor =
      system.actorOf(Props.create(MyUntypedActor.class),
        "myactor");
    //#defining-dispatcher-in-config
  }

  @SuppressWarnings("unused")
  @Test
  public void defineDispatcherInCode() {
    //#defining-dispatcher-in-code
    ActorRef myActor =
      system.actorOf(Props.create(MyUntypedActor.class).withDispatcher("my-dispatcher"),
        "myactor3");
    //#defining-dispatcher-in-code
  }

  @SuppressWarnings("unused")
  @Test
  public void defineFixedPoolSizeDispatcher() {
    //#defining-fixed-pool-size-dispatcher
    ActorRef myActor = system.actorOf(Props.create(MyUntypedActor.class)
        .withDispatcher("blocking-io-dispatcher"));
    //#defining-fixed-pool-size-dispatcher
  }

  @SuppressWarnings("unused")
  @Test
  public void definePinnedDispatcher() {
    //#defining-pinned-dispatcher
    ActorRef myActor = system.actorOf(Props.create(MyUntypedActor.class)
        .withDispatcher("my-pinned-dispatcher"));
    //#defining-pinned-dispatcher
  }

  @SuppressWarnings("unused")
  public void compileLookup() {
    //#lookup
    // this is scala.concurrent.ExecutionContext
    // for use with Futures, Scheduler, etc.
    final ExecutionContext ex = system.dispatchers().lookup("my-dispatcher");
    //#lookup
  }

  @SuppressWarnings("unused")
  @Test
  public void defineMailboxInConfig() {
    //#defining-mailbox-in-config
    ActorRef myActor =
      system.actorOf(Props.create(MyUntypedActor.class),
        "priomailboxactor");
    //#defining-mailbox-in-config
  }

  @SuppressWarnings("unused")
  @Test
  public void defineMailboxInCode() {
    //#defining-mailbox-in-code
    ActorRef myActor =
      system.actorOf(Props.create(MyUntypedActor.class)
        .withMailbox("prio-mailbox"));
    //#defining-mailbox-in-code
  }

  @SuppressWarnings("unused")
  @Test
  public void usingARequiredMailbox() {
    ActorRef myActor =
      system.actorOf(Props.create(MyBoundedUntypedActor.class));
  }

  @Test
  public void priorityDispatcher() throws Exception {
    JavaTestKit probe = new JavaTestKit(system);
    //#prio-dispatcher

    class Demo extends UntypedActor {
      LoggingAdapter log = Logging.getLogger(getContext().system(), this);
      {
        for (Object msg : new Object[] { "lowpriority", "lowpriority",
            "highpriority", "pigdog", "pigdog2", "pigdog3", "highpriority",
            PoisonPill.getInstance() }) {
          getSelf().tell(msg, getSelf());
        }
      }

      public void onReceive(Object message) {
        log.info(message.toString());
      }
    }

    // We create a new Actor that just prints out what it processes
    ActorRef myActor = system.actorOf(Props.create(Demo.class, this)
        .withDispatcher("prio-dispatcher"));

    /*
    Logs:
      'highpriority
      'highpriority
      'pigdog
      'pigdog2
      'pigdog3
      'lowpriority
      'lowpriority
    */
    //#prio-dispatcher

    probe.watch(myActor);
    probe.expectMsgClass(Terminated.class);
  }

  @Test
  public void controlAwareDispatcher() throws Exception {
    JavaTestKit probe = new JavaTestKit(system);
    //#control-aware-dispatcher

    class Demo extends UntypedActor {
      LoggingAdapter log = Logging.getLogger(getContext().system(), this);
      {
        for (Object msg : new Object[] { "foo", "bar", new MyControlMessage(),
                PoisonPill.getInstance() }) {
          getSelf().tell(msg, getSelf());
        }
      }

      public void onReceive(Object message) {
        log.info(message.toString());
      }
    }

    // We create a new Actor that just prints out what it processes
    ActorRef myActor = system.actorOf(Props.create(Demo.class, this)
            .withDispatcher("control-aware-dispatcher"));

    /*
    Logs:
      'MyControlMessage
      'foo
      'bar
    */
    //#control-aware-dispatcher

    probe.watch(myActor);
    probe.expectMsgClass(Terminated.class);
  }

  static
  //#prio-mailbox
  public class MyPrioMailbox extends UnboundedStablePriorityMailbox {
    // needed for reflective instantiation
    public MyPrioMailbox(ActorSystem.Settings settings, Config config) {
      // Create a new PriorityGenerator, lower prio means more important
      super(new PriorityGenerator() {
        @Override
        public int gen(Object message) {
          if (message.equals("highpriority"))
            return 0; // 'highpriority messages should be treated first if possible
          else if (message.equals("lowpriority"))
            return 2; // 'lowpriority messages should be treated last if possible
          else if (message.equals(PoisonPill.getInstance()))
            return 3; // PoisonPill when no other left
          else
            return 1; // By default they go between high and low prio
        }
      });
    }
  }
  //#prio-mailbox

  static
  //#control-aware-mailbox-messages
  public class MyControlMessage implements ControlMessage {}
  //#control-aware-mailbox-messages

  @Test
  public void requiredMailboxDispatcher() throws Exception {
    ActorRef myActor = system.actorOf(Props.create(MyUntypedActor.class)
      .withDispatcher("custom-dispatcher"));
  }

  static
  //#require-mailbox-on-actor
  public class MySpecialActor extends UntypedActor implements
    RequiresMessageQueue<MyUnboundedJMessageQueueSemantics> {
    //#require-mailbox-on-actor
    @Override
    public void onReceive(Object message) throws Exception {
      unhandled(message);
    }
    //#require-mailbox-on-actor
    // ...
  }
  //#require-mailbox-on-actor

  @Test
  public void requiredMailboxActor() throws Exception {
    ActorRef myActor = system.actorOf(Props.create(MySpecialActor.class));
  }

}
