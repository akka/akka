/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.remoting;

import akka.testkit.AkkaJUnitActorSystemResource;
import jdocs.AbstractJavaTest;
import org.junit.ClassRule;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

// #import
import akka.actor.ActorRef;
import akka.actor.Address;
import akka.actor.AddressFromURIString;
import akka.actor.Deploy;
import akka.actor.Props;
import akka.actor.ActorSystem;
import akka.remote.RemoteScope;
// #import

import akka.actor.AbstractActor;

public class RemoteDeploymentDocTest extends AbstractJavaTest {

  public static class SampleActor extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder().matchAny(message -> getSender().tell(getSelf(), getSelf())).build();
    }
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("RemoteDeploymentDocTest");

  private final ActorSystem system = actorSystemResource.getSystem();

  @SuppressWarnings("unused")
  void makeAddress() {
    // #make-address-artery
    Address addr = new Address("akka", "sys", "host", 1234);
    addr = AddressFromURIString.parse("akka://sys@host:1234"); // the same
    // #make-address-artery
  }

  @Test
  public void demonstrateDeployment() {
    // #make-address
    Address addr = new Address("akka.tcp", "sys", "host", 1234);
    addr = AddressFromURIString.parse("akka.tcp://sys@host:1234"); // the same
    // #make-address
    // #deploy
    ActorRef ref =
        system.actorOf(
            Props.create(SampleActor.class).withDeploy(new Deploy(new RemoteScope(addr))));
    // #deploy
    assert ref.path().address().equals(addr);
  }

  @Test
  public void demonstrateSampleActor() {
    // #sample-actor

    ActorRef actor = system.actorOf(Props.create(SampleActor.class), "sampleActor");
    actor.tell("Pretty slick", ActorRef.noSender());
    // #sample-actor
  }

  @Test
  public void demonstrateProgrammaticConfig() {
    // #programmatic
    ConfigFactory.parseString("akka.remote.netty.tcp.hostname=\"1.2.3.4\"")
        .withFallback(ConfigFactory.load());
    // #programmatic

    // #programmatic-artery
    ConfigFactory.parseString("akka.remote.artery.canonical.hostname=\"1.2.3.4\"")
        .withFallback(ConfigFactory.load());
    // #programmatic-artery
  }
}
