/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.dispatcher;

//#imports
import akka.actor.*;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
//#imports

//#imports-prio
import akka.event.Logging;
import akka.event.LoggingAdapter;

//#imports-prio

//#imports-prio-mailbox
import akka.dispatch.PriorityGenerator;
import akka.dispatch.UnboundedPriorityMailbox;
import com.typesafe.config.Config;

//#imports-prio-mailbox

//#imports-custom
import akka.dispatch.Envelope;
import akka.dispatch.MessageQueue;
import akka.dispatch.MailboxType;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

//#imports-custom

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scala.Option;

import com.typesafe.config.ConfigFactory;

import docs.actor.MyUntypedActor;
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
                getSelf().tell("lowpriority", getSelf());
                getSelf().tell("lowpriority", getSelf());
                getSelf().tell("highpriority", getSelf());
                getSelf().tell("pigdog", getSelf());
                getSelf().tell("pigdog2", getSelf());
                getSelf().tell("pigdog3", getSelf());
                getSelf().tell("highpriority", getSelf());
                getSelf().tell(PoisonPill.getInstance(), getSelf());
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
    public MyPrioMailbox(ActorSystem.Settings settings, Config config) { // needed for reflective instantiation
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
  
  //#mailbox-implementation-example
  class MyUnboundedMailbox implements MailboxType {

    // This constructor signature must exist, it will be called by Akka
    public MyUnboundedMailbox(ActorSystem.Settings settings, Config config) {
      // put your initialization code here
    }

    // The create method is called to create the MessageQueue
    public MessageQueue create(Option<ActorRef> owner, Option<ActorSystem> system) {
      return new MessageQueue() {
        private final Queue<Envelope> queue = new ConcurrentLinkedQueue<Envelope>();
        
        // these must be implemented; queue used as example
        public void enqueue(ActorRef receiver, Envelope handle) { queue.offer(handle); }
        public Envelope dequeue() { return queue.poll(); }
        public int numberOfMessages() { return queue.size(); }
        public boolean hasMessages() { return !queue.isEmpty(); }
        public void cleanUp(ActorRef owner, MessageQueue deadLetters) {
          for (Envelope handle: queue) {
            deadLetters.enqueue(owner, handle);
          }
        }
      };
    }
  }
  //#mailbox-implementation-example
}
