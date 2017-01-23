/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.event;

//#imports
import akka.actor.*;
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

import docs.AbstractJavaTest;
import org.junit.Test;
import akka.testkit.JavaTestKit;
import java.util.Optional;

//#imports-mdc
import akka.event.Logging;
import akka.event.DiagnosticLoggingAdapter;
import java.util.HashMap;
import java.util.Map;
//#imports-mdc

//#imports-deadletter
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
//#imports-deadletter

public class LoggingDocTest extends AbstractJavaTest {

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

  //#superclass-subscription-eventstream
  interface AllKindsOfMusic { }

  class Jazz implements AllKindsOfMusic {
    final public String artist;
    public Jazz(String artist) {
      this.artist = artist;
    }
  }
  class Electronic implements AllKindsOfMusic {
    final public String artist;
    public Electronic(String artist) {
      this.artist = artist;
    }
  }

  static class Listener extends AbstractActor {
    @Override
      public Receive createReceive() {
        return receiveBuilder()
          .match(Jazz.class, msg -> 
            System.out.printf("%s is listening to: %s%n", self().path().name(), msg)
          )
          .match(Electronic.class, msg -> 
            System.out.printf("%s is listening to: %s%n", self().path().name(), msg)
          )
          .build();
      }
  }
  //#superclass-subscription-eventstream

  @Test
  public void subscribeBySubclassification() {
    final ActorSystem system = ActorSystem.create("DeadLetters");
    //#superclass-subscription-eventstream
    final ActorRef actor = system.actorOf(Props.create(DeadLetterActor.class));
    system.eventStream().subscribe(actor, DeadLetter.class);

    final ActorRef jazzListener = system.actorOf(Props.create(Listener.class));
    final ActorRef musicListener = system.actorOf(Props.create(Listener.class));
    system.eventStream().subscribe(jazzListener, Jazz.class);
    system.eventStream().subscribe(musicListener, AllKindsOfMusic.class);

    // only musicListener gets this message, since it listens to *all* kinds of music:
    system.eventStream().publish(new Electronic("Parov Stelar"));

    // jazzListener and musicListener will be notified about Jazz:
    system.eventStream().publish(new Jazz("Sonny Rollins"));

    //#superclass-subscription-eventstream
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
  class MyActor extends AbstractActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    @Override
    public void preStart() {
      log.debug("Starting");
    }

    @Override
    public void preRestart(Throwable reason, Optional<Object> message) {
      log.error(reason, "Restarting due to [{}] when processing [{}]",
        reason.getMessage(), message.isPresent() ? message.get() : "");
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .matchEquals("test", msg ->
          log.info("Received test")
        )
        .matchAny(msg ->
          log.warning("Received unknown message: {}", msg)
        )
        .build();
    }
  }

  //#my-actor

  //#mdc-actor
  class MdcActor extends AbstractActor {

      final DiagnosticLoggingAdapter log = Logging.getLogger(this);

      @Override
      public Receive createReceive() {
        return receiveBuilder()
          .matchAny(msg -> {
            Map<String, Object> mdc;
            mdc = new HashMap<String, Object>();
            mdc.put("requestId", 1234);
            mdc.put("visitorId", 5678);
            log.setMDC(mdc);

            log.info("Starting new request");

            log.clearMDC();
          })
          .build();
      }
  }

  //#mdc-actor

  //#my-event-listener
  class MyEventListener extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(InitializeLogger.class, msg -> {
          sender().tell(Logging.loggerInitialized(), self());
        })
        .match(Error.class, msg -> {
          // ...
        })
        .match(Warning.class, msg -> {
          // ...           
        })
        .match(Info.class, msg -> {
          // ...           
        })
        .match(Debug.class, msg -> {
          // ...
        })
        .build();
    }
  }
  //#my-event-listener

  static
  //#deadletter-actor
  public class DeadLetterActor extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(DeadLetter.class, msg -> {
          System.out.println(msg);
        })
        .build();
    }
  }
  //#deadletter-actor
}
