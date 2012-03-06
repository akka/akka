/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.event;

//#imports
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

import scala.Option;
import static org.junit.Assert.*;

import akka.actor.UntypedActorFactory;
//#imports-deadletter
import akka.actor.Props;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
import akka.actor.DeadLetter;
//#imports-deadletter

public class LoggingDocTestBase {
  
  @Test
  public void useLoggingActor() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyActor();
      }
    }));
    myActor.tell("test");
    system.shutdown();
  }

  @Test
  public void subscribeToDeadLetters() {
    //#deadletters
    final ActorSystem system = ActorSystem.create("DeadLetters");
    final ActorRef actor = system.actorOf(new Props(DeadLetterActor.class));
    system.eventStream().subscribe(actor, DeadLetter.class);
    //#deadletters
    system.shutdown();
  }
  
  @Test
  public void demonstrateMultipleArgs() {
    final ActorSystem system = ActorSystem.create("multiArg");
    //#array
    final Object[] args = new Object[] { "The", "brown", "fox", "jumps", 42 };
    system.log().debug("five parameters: {}, {}, {}, {}, {}", args);
    //#array
    system.shutdown();
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
      log.error(reason, "Restarting due to [{}] when processing [{}]", reason.getMessage(),
          message.isDefined() ? message.get() : "");
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

  //#my-event-listener
  class MyEventListener extends UntypedActor {
    public void onReceive(Object message) {
      if (message instanceof InitializeLogger) {
        getSender().tell(Logging.loggerInitialized());
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

  //#deadletter-actor
  public static class DeadLetterActor extends UntypedActor {
    public void onReceive(Object message) {
      if (message instanceof DeadLetter) {
        System.out.println(message);
      }
    }
  }
  //#deadletter-actor

}
