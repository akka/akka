/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.remoting;

import org.junit.AfterClass;
import org.junit.BeforeClass;
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

public class RemoteDeploymentDocTestBase {

  public static class SampleActor extends UntypedActor {
    public void onReceive(Object message) {
      getSender().tell(getSelf(), getSelf());
    }
  }
  
  static ActorSystem system;
  
  @BeforeClass
  public static void init() {
    system = ActorSystem.create();
  }
  
  @AfterClass
  public static void cleanup() {
    system.shutdown();
  }
  
  @Test
  public void demonstrateDeployment() {
    //#make-address
    Address addr = new Address("akka", "sys", "host", 1234);
    addr = AddressFromURIString.parse("akka://sys@host:1234"); // the same
    //#make-address
    //#deploy
    ActorRef ref = system.actorOf(new Props(SampleActor.class).withDeploy(
      new Deploy(new RemoteScope(addr))));
    //#deploy
    assert ref.path().address().equals(addr);
  }

  @Test
  public void demonstrateSampleActor() {
    //#sample-actor

    ActorRef actor = system.actorOf(new Props(SampleActor.class), "sampleActor");
    actor.tell("Pretty slick", null);
    //#sample-actor
  }
  
  @Test
  public void demonstrateProgrammaticConfig() {
    //#programmatic
    ConfigFactory.parseString("akka.remote.netty.hostname=\"1.2.3.4\"")
        .withFallback(ConfigFactory.load());
    //#programmatic
  }

  
}