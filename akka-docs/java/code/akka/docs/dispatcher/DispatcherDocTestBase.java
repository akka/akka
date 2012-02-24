/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.dispatcher;

//#imports
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.dispatch.MessageDispatcher;

//#imports

//#imports-prio
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.actor.Actors;
import akka.event.Logging;
import akka.event.LoggingAdapter;

//#imports-prio

//#imports-prio-mailbox
import akka.actor.ActorContext;
import akka.dispatch.PriorityGenerator;
import akka.dispatch.UnboundedPriorityMailbox;
import akka.dispatch.MailboxType;
import akka.dispatch.MessageQueue;
import com.typesafe.config.Config;

//#imports-prio-mailbox

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scala.Option;
import static org.junit.Assert.*;

import com.typesafe.config.ConfigFactory;

import akka.actor.ActorSystem;
import akka.docs.actor.MyUntypedActor;
import akka.docs.actor.UntypedActorDocTestBase.MyActor;
import akka.testkit.AkkaSpec;

public class DispatcherDocTestBase {

  ActorSystem system;

  @Before
  public void setUp() {
    system = ActorSystem.create("MySystem",
        ConfigFactory.parseString(DispatcherDocSpec.config()).withFallback(AkkaSpec.testConf()));
  }

  @After
  public void tearDown() {
    system.shutdown();
  }

  @Test
  public void defineDispatcher() {
    //#defining-dispatcher
    ActorRef myActor =
      system.actorOf(new Props(MyUntypedActor.class).withDispatcher("my-dispatcher"),
        "myactor3");
    //#defining-dispatcher
  }

  @Test
  public void definePinnedDispatcher() {
    //#defining-pinned-dispatcher
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class)
        .withDispatcher("my-pinned-dispatcher"));
    //#defining-pinned-dispatcher
  }

  @Test
  public void priorityDispatcher() throws Exception {
    //#prio-dispatcher

      // We create a new Actor that just prints out what it processes
    ActorRef myActor = system.actorOf(
        new Props().withCreator(new UntypedActorFactory() {
          public UntypedActor create() {
            return new UntypedActor() {
              LoggingAdapter log =
                      Logging.getLogger(getContext().system(), this);
              {
                getSelf().tell("lowpriority");
                getSelf().tell("lowpriority");
                getSelf().tell("highpriority");
                getSelf().tell("pigdog");
                getSelf().tell("pigdog2");
                getSelf().tell("pigdog3");
                getSelf().tell("highpriority");
                getSelf().tell(Actors.poisonPill());
              }

              public void onReceive(Object message) {
                log.info(message.toString());
              }
            };
          }
        }).withDispatcher("prio-dispatcher"));

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

    for (int i = 0; i < 10; i++) {
      if (myActor.isTerminated())
        break;
      Thread.sleep(100);
    }
  }

  //#prio-mailbox
  public static class MyPrioMailbox extends UnboundedPriorityMailbox {
    public MyPrioMailbox(Config config) { // needed for reflective instantiation
      // Create a new PriorityGenerator, lower prio means more important
      super(new PriorityGenerator() {
        @Override
        public int gen(Object message) {
          if (message.equals("highpriority"))
            return 0; // 'highpriority messages should be treated first if possible
          else if (message.equals("lowpriority"))
            return 2; // 'lowpriority messages should be treated last if possible
          else if (message.equals(Actors.poisonPill()))
            return 3; // PoisonPill when no other left
          else
            return 1; // By default they go between high and low prio
        }
      });
    }
  }
  //#prio-mailbox
}
