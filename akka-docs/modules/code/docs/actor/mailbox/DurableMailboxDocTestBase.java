/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor.mailbox;

//#imports
import akka.actor.UntypedActorFactory;
import akka.actor.UntypedActor;
import akka.actor.Props;

//#imports

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import akka.testkit.AkkaSpec;
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
    ActorRef myActor = system.actorOf(
        new Props().withDispatcher("my-dispatcher").withCreator(new UntypedActorFactory() {
          public UntypedActor create() {
            return new MyUntypedActor();
          }
        }), "myactor");
    //#dispatcher-config-use
    myActor.tell("test");
  }

  public static class MyUntypedActor extends UntypedActor {
    public void onReceive(Object message) {
    }
  }

}
