/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.client;

import akka.actor.*;
import com.typesafe.config.ConfigFactory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.ClassRule;
import org.junit.Test;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.scalatest.junit.JUnitSuite;

public class ClusterClientTest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("DistributedPubSubMediatorTest",
        ConfigFactory.parseString(
            "akka.actor.provider = \"cluster\"\n" +
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

    system.actorOf(Props.create(ClientListener.class, c));
    system.actorOf(Props.create(ReceptionistListener.class, ClusterClientReceptionist.get(system).underlying()));
  }

  static public class Service extends UntypedActor {
    public void onReceive(Object msg) {
    }
  }

  //#clientEventsListener
  static public class ClientListener extends UntypedActor {
    private final ActorRef targetClient;
    private final Set<ActorPath> contactPoints = new HashSet<>();

    public ClientListener(ActorRef targetClient) {
      this.targetClient = targetClient;
    }

    @Override
    public void preStart() {
      targetClient.tell(SubscribeContactPoints.getInstance(), sender());
    }

    @Override
    public void onReceive(Object message) {
      if (message instanceof ContactPoints) {
        ContactPoints msg = (ContactPoints)message;
        contactPoints.addAll(msg.getContactPoints());
        // Now do something with an up-to-date "contactPoints"
      } else if (message instanceof ContactPointAdded) {
        ContactPointAdded msg = (ContactPointAdded) message;
        contactPoints.add(msg.contactPoint());
        // Now do something with an up-to-date "contactPoints"
      } else if (message instanceof ContactPointRemoved) {
        ContactPointRemoved msg = (ContactPointRemoved)message;
        contactPoints.remove(msg.contactPoint());
        // Now do something with an up-to-date "contactPoints"
      }
    }
  }
  //#clientEventsListener

  //#receptionistEventsListener
  static public class ReceptionistListener extends UntypedActor {
    private final ActorRef targetReceptionist;
    private final Set<ActorRef> clusterClients = new HashSet<>();

    public ReceptionistListener(ActorRef targetReceptionist) {
      this.targetReceptionist = targetReceptionist;
    }

    @Override
    public void preStart() {
      targetReceptionist.tell(SubscribeClusterClients.getInstance(), sender());
    }

    @Override
    public void onReceive(Object message) {
      if (message instanceof ClusterClients) {
        ClusterClients msg = (ClusterClients) message;
        clusterClients.addAll(msg.getClusterClients());
        // Now do something with an up-to-date "clusterClients"
      } else if (message instanceof ClusterClientUp) {
        ClusterClientUp msg = (ClusterClientUp) message;
        clusterClients.add(msg.clusterClient());
        // Now do something with an up-to-date "clusterClients"
      } else if (message instanceof ClusterClientUnreachable) {
        ClusterClientUnreachable msg = (ClusterClientUnreachable) message;
        clusterClients.remove(msg.clusterClient());
        // Now do something with an up-to-date "clusterClients"
      }
    }
  }
  //#receptionistEventsListener

}
