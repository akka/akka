/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor.mailbox;

//#imports
import akka.actor.Props;
import akka.actor.ActorRef;

//#imports

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import akka.testkit.AkkaSpec;
import com.typesafe.config.ConfigFactory;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;

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
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class).
        withDispatcher("my-dispatcher"), "myactor");
    //#dispatcher-config-use
    myActor.tell("test");
  }

  public static class MyUntypedActor extends UntypedActor {
    public void onReceive(Object message) {
    }
  }

}
