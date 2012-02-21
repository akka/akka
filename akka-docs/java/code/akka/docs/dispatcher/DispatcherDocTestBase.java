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
    ActorRef myActor1 = system.actorOf(new Props(MyUntypedActor.class).withDispatcher("my-dispatcher"),
        "myactor1");
    ActorRef myActor2 = system.actorOf(new Props(MyUntypedActor.class).withDispatcher("my-dispatcher"),
        "myactor2");
    //#defining-dispatcher
  }

  @Test
  public void definePinnedDispatcher() {
    //#defining-pinned-dispatcher
    String name = "myactor";
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class)
        .withDispatcher("myactor-dispatcher"), name);
    //#defining-pinned-dispatcher
  }

  @Test
  public void priorityDispatcher() throws Exception {
    //#prio-dispatcher

    ActorRef myActor = system.actorOf( // We create a new Actor that just prints out what it processes
        new Props().withCreator(new UntypedActorFactory() {
          public UntypedActor create() {
            return new UntypedActor() {
              LoggingAdapter log = Logging.getLogger(getContext().system(), this);
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
        }).withDispatcher("prio-dispatcher-java"));

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
  public static class PrioMailbox extends UnboundedPriorityMailbox {
    public PrioMailbox(Config config) { // needed for reflective instantiation
      super(new PriorityGenerator() { // Create a new PriorityGenerator, lower prio means more important
        @Override
        public int gen(Object message) {
          if (message.equals("highpriority"))
            return 0; // 'highpriority messages should be treated first if possible
          else if (message.equals("lowpriority"))
            return 100; // 'lowpriority messages should be treated last if possible
          else if (message.equals(Actors.poisonPill()))
            return 1000; // PoisonPill when no other left
          else
            return 50; // We default to 50
        }
      });
    }
  }
  //#prio-mailbox
}
