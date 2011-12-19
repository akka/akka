/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor.mailbox;

//#imports
import akka.dispatch.MessageDispatcher;
import akka.actor.UntypedActorFactory;
import akka.actor.UntypedActor;
import akka.actor.Props;

//#imports

//#imports2
import akka.actor.mailbox.DurableMailboxType;
//#imports2

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import akka.testkit.AkkaSpec;
import akka.docs.dispatcher.DispatcherDocSpec;
import com.typesafe.config.ConfigFactory;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;

import static org.junit.Assert.*;

public class DurableMailboxDocTestBase {

  ActorSystem system;

  @Before
  public void setUp() {
    system = ActorSystem.create("MySystem",
        ConfigFactory.parseString(DurableMailboxDocSpec.config()).withFallback(AkkaSpec.testConf()));
  }

  @After
  public void tearDown() {
    system.shutdown();
  }

  @Test
  public void configDefinedDispatcher() {
    //#dispatcher-config-use
    MessageDispatcher dispatcher = system.dispatcherFactory().lookup("my-dispatcher");
    ActorRef myActor = system.actorOf(new Props().withDispatcher(dispatcher).withCreator(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyUntypedActor();
      }
    }), "myactor");
    //#dispatcher-config-use
    myActor.tell("test");
  }

  @Test
  public void programaticallyDefinedDispatcher() {
    //#prog-define-dispatcher
    MessageDispatcher dispatcher = system.dispatcherFactory()
        .newDispatcher("my-dispatcher", 1, DurableMailboxType.fileDurableMailboxType()).build();
    ActorRef myActor = system.actorOf(new Props().withDispatcher(dispatcher).withCreator(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyUntypedActor();
      }
    }), "myactor");
    //#prog-define-dispatcher
    myActor.tell("test");
  }

  public static class MyUntypedActor extends UntypedActor {
    public void onReceive(Object message) {
    }
  }
}
