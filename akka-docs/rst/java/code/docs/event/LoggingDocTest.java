/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.event;

//#imports
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.AllDeadLetters;
import akka.actor.SuppressedDeadLetter;
import akka.event.Logging;
import akka.event.LoggingAdapter;

//#imports

//#imports-listener
import akka.event.Logging.InitializeLogger;
import akka.event.Logging.Error;
import akka.event.Logging.Warning;
import akka.event.Logging.Info;
import akka.event.Logging.Debug;

//#imports-listener

import org.junit.Test;
import akka.testkit.JavaTestKit;
import scala.Option;

//#imports-mdc
import akka.event.Logging;
import akka.event.DiagnosticLoggingAdapter;
import java.util.HashMap;
import java.util.Map;
//#imports-mdc

//#imports-deadletter
import akka.actor.Props;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
import akka.actor.DeadLetter;
//#imports-deadletter

public class LoggingDocTest {
  
  @Test
  public void useLoggingActor() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(Props.create(MyActor.class, this));
    myActor.tell("test", ActorRef.noSender());
    JavaTestKit.shutdownActorSystem(system);
  }

  @Test
  public void useLoggingActorWithMDC() {
    ActorSystem system = ActorSystem.create("MyDiagnosticSystem");
    ActorRef mdcActor = system.actorOf(Props.create(MdcActor.class, this));
    mdcActor.tell("some request", ActorRef.noSender());
    JavaTestKit.shutdownActorSystem(system);
  }

  @Test
  public void subscribeToDeadLetters() {
    //#deadletters
    final ActorSystem system = ActorSystem.create("DeadLetters");
    final ActorRef actor = system.actorOf(Props.create(DeadLetterActor.class));
    system.eventStream().subscribe(actor, DeadLetter.class);
    //#deadletters
    JavaTestKit.shutdownActorSystem(system);
  }

  @Test
  public void subscribeToSuppressedDeadLetters() {
    final ActorSystem system = ActorSystem.create("SuppressedDeadLetters");
    final ActorRef actor = system.actorOf(Props.create(DeadLetterActor.class));
    
    //#suppressed-deadletters
    system.eventStream().subscribe(actor, SuppressedDeadLetter.class);
    //#suppressed-deadletters

    JavaTestKit.shutdownActorSystem(system);
  }
  @Test
  public void subscribeToAllDeadLetters() {
    final ActorSystem system = ActorSystem.create("AllDeadLetters");
    final ActorRef actor = system.actorOf(Props.create(DeadLetterActor.class));

    //#all-deadletters
    system.eventStream().subscribe(actor, AllDeadLetters.class);
    //#all-deadletters

    JavaTestKit.shutdownActorSystem(system);
  }

  @Test
  public void demonstrateMultipleArgs() {
    final ActorSystem system = ActorSystem.create("multiArg");
    //#array
    final Object[] args = new Object[] { "The", "brown", "fox", "jumps", 42 };
    system.log().debug("five parameters: {}, {}, {}, {}, {}", args);
    //#array
    JavaTestKit.shutdownActorSystem(system);
  }

  //#my-actor
  class MyActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    @Override
    public void preStart() {
      log.debug("Starting");
    }

    @Override
    public void preRestart(Throwable reason, Option<Object> message) {
      log.error(reason, "Restarting due to [{}] when processing [{}]",
        reason.getMessage(), message.isDefined() ? message.get() : "");
    }

    public void onReceive(Object message) {
      if (message.equals("test")) {
        log.info("Received test");
      } else {
        log.warning("Received unknown message: {}", message);
      }
    }
  }

  //#my-actor

  //#mdc-actor
  class MdcActor extends UntypedActor {

      final DiagnosticLoggingAdapter log = Logging.getLogger(this);

      public void onReceive(Object message) {

          Map<String, Object> mdc;
          mdc = new HashMap<String, Object>();
          mdc.put("requestId", 1234);
          mdc.put("visitorId", 5678);
          log.setMDC(mdc);

          log.info("Starting new request");

          log.clearMDC();
      }
  }

  //#mdc-actor

  //#my-event-listener
  class MyEventListener extends UntypedActor {
    public void onReceive(Object message) {
      if (message instanceof InitializeLogger) {
        getSender().tell(Logging.loggerInitialized(), getSelf());
      } else if (message instanceof Error) {
        // ...
      } else if (message instanceof Warning) {
        // ...
      } else if (message instanceof Info) {
        // ...
      } else if (message instanceof Debug) {
        // ...
      }
    }
  }
  //#my-event-listener

  static
  //#deadletter-actor
  public class DeadLetterActor extends UntypedActor {
    public void onReceive(Object message) {
      if (message instanceof DeadLetter) {
        System.out.println(message);
      }
    }
  }
  //#deadletter-actor
}
