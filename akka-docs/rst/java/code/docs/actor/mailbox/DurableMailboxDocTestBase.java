/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor.mailbox;

//#imports
import akka.actor.Props;
import akka.actor.ActorRef;

//#imports

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import org.junit.Test;

import akka.testkit.AkkaSpec;
import com.typesafe.config.ConfigFactory;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;

public class DurableMailboxDocTestBase {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("DurableMailboxDocTest",
      ConfigFactory.parseString(DurableMailboxDocSpec.config()).withFallback(AkkaSpec.testConf()));

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void configDefinedDispatcher() {
    //#dispatcher-config-use
    ActorRef myActor = system.actorOf(Props.create(MyUntypedActor.class).
        withDispatcher("my-dispatcher"), "myactor");
    //#dispatcher-config-use
    myActor.tell("test", null);
  }

  public static class MyUntypedActor extends UntypedActor {
    public void onReceive(Object message) {
    }
  }

}
