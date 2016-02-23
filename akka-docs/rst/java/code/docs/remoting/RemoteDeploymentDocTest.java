/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.remoting;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

//#import
import akka.actor.ActorRef;
import akka.actor.Address;
import akka.actor.AddressFromURIString;
import akka.actor.Deploy;
import akka.actor.Props;
import akka.actor.ActorSystem;
import akka.remote.RemoteScope;
//#import

import akka.actor.UntypedActor;

public class RemoteDeploymentDocTest {

  public static class SampleActor extends UntypedActor {
    public void onReceive(Object message) {
      getSender().tell(getSelf(), getSelf());
    }
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("RemoteDeploymentDocTest");

  private final ActorSystem system = actorSystemResource.getSystem();
  
  @Test
  public void demonstrateDeployment() {
    //#make-address
    Address addr = new Address("akka.tcp", "sys", "host", 1234);
    addr = AddressFromURIString.parse("akka.tcp://sys@host:1234"); // the same
    //#make-address
    //#deploy
    ActorRef ref = system.actorOf(Props.create(SampleActor.class).withDeploy(
      new Deploy(new RemoteScope(addr))));
    //#deploy
    assert ref.path().address().equals(addr);
  }

  @Test
  public void demonstrateSampleActor() {
    //#sample-actor

    ActorRef actor = system.actorOf(Props.create(SampleActor.class), "sampleActor");
    actor.tell("Pretty slick", ActorRef.noSender());
    //#sample-actor
  }
  
  @Test
  public void demonstrateProgrammaticConfig() {
    //#programmatic
    ConfigFactory.parseString("akka.remote.netty.tcp.hostname=\"1.2.3.4\"")
        .withFallback(ConfigFactory.load());
    //#programmatic
  }

  
}
