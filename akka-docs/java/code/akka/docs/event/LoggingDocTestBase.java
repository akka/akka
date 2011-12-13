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

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;

public class LoggingDocTestBase {

  @Test
  public void useLoggingActor() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyActor();
      }
    });
    myActor.tell("test");
    system.stop();
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

}
