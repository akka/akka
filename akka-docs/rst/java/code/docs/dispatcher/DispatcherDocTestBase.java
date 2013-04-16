/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.dispatcher;

//#imports
import akka.actor.*;
//#imports

//#imports-prio
import akka.event.Logging;
import akka.event.LoggingAdapter;

//#imports-prio

//#imports-prio-mailbox
import akka.dispatch.PriorityGenerator;
import akka.dispatch.UnboundedPriorityMailbox;
import akka.testkit.JavaTestKit;
import com.typesafe.config.Config;

//#imports-prio-mailbox

//#imports-custom
import akka.dispatch.Envelope;
import akka.dispatch.MessageQueue;
import akka.dispatch.MailboxType;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

//#imports-custom

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.Option;
import scala.concurrent.ExecutionContext;

import com.typesafe.config.ConfigFactory;

import docs.actor.MyUntypedActor;
import akka.testkit.AkkaSpec;

public class DispatcherDocTestBase {

  static ActorSystem system;

  @BeforeClass
  public static void beforeAll() {
    system = ActorSystem.create("MySystem",
        ConfigFactory.parseString(
          DispatcherDocSpec.config()).withFallback(AkkaSpec.testConf()));
  }

  @AfterClass
  public static void afterAll() {
    system.shutdown();
    system = null;
  }

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

  static
  //#prio-mailbox
  public class MyPrioMailbox extends UnboundedPriorityMailbox {
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
  //#mailbox-implementation-example
  public class MyUnboundedMailbox implements MailboxType {

    // This constructor signature must exist, it will be called by Akka
    public MyUnboundedMailbox(ActorSystem.Settings settings, Config config) {
      // put your initialization code here
    }

    // The create method is called to create the MessageQueue
    public MessageQueue create(Option<ActorRef> owner, Option<ActorSystem> system) {
      return new MessageQueue() {
        private final Queue<Envelope> queue = new ConcurrentLinkedQueue<Envelope>();
        
        // these must be implemented; queue used as example
        public void enqueue(ActorRef receiver, Envelope handle) {
          queue.offer(handle);
        }
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
