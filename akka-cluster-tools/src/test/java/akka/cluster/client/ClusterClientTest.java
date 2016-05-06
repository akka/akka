/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.client;

import com.typesafe.config.ConfigFactory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.ClassRule;
import org.junit.Test;

import akka.actor.ActorPath;
import akka.actor.ActorPaths;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.testkit.AkkaJUnitActorSystemResource;
import org.scalatest.junit.JUnitSuite;

public class ClusterClientTest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("DistributedPubSubMediatorTest",
        ConfigFactory.parseString(
            "akka.actor.provider = \"akka.cluster.ClusterActorRefProvider\"\n" +
            "akka.remote.netty.tcp.port=0"));

  private final ActorSystem system = actorSystemResource.getSystem();

  //#initialContacts
  Set<ActorPath> initialContacts() {
    return new HashSet<ActorPath>(Arrays.asList(
    ActorPaths.fromString("akka.tcp://OtherSys@host1:2552/system/receptionist"),
    ActorPaths.fromString("akka.tcp://OtherSys@host2:2552/system/receptionist")));
  }
  //#initialContacts


  @Test
  public void demonstrateUsage() {
    //#server
    ActorRef serviceA = system.actorOf(Props.create(Service.class), "serviceA");
    ClusterClientReceptionist.get(system).registerService(serviceA);

    ActorRef serviceB = system.actorOf(Props.create(Service.class), "serviceB");
    ClusterClientReceptionist.get(system).registerService(serviceB);
    //#server

    //#client
    final ActorRef c = system.actorOf(ClusterClient.props(
        ClusterClientSettings.create(system).withInitialContacts(initialContacts())),
        "client");
    c.tell(new ClusterClient.Send("/user/serviceA", "hello", true), ActorRef.noSender());
    c.tell(new ClusterClient.SendToAll("/user/serviceB", "hi"), ActorRef.noSender());
    //#client
  }

  static public class Service extends UntypedActor {
    public void onReceive(Object msg) {
    }
  }
}
