/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.singleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.Member;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberEvent;
import akka.cluster.ClusterEvent.MemberUp;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.MemberStatus;

public class ClusterSingletonManagerTest {

  public void demo() {
    final ActorSystem system = null;
    final ActorRef queue = null;
    final ActorRef testActor = null;

    //#create-singleton-manager
    final ClusterSingletonManagerSettings settings =
      ClusterSingletonManagerSettings.create(system).withRole("worker");
    system.actorOf(ClusterSingletonManager.props(
      Props.create(Consumer.class, queue, testActor),
      new End(), settings), "consumer");
    //#create-singleton-manager

    //#create-singleton-proxy
    ClusterSingletonProxySettings proxySettings =
        ClusterSingletonProxySettings.create(system).withRole("worker");
    system.actorOf(ClusterSingletonProxy.props("/user/consumer", proxySettings), 
        "consumerProxy");
    //#create-singleton-proxy
  }

  public static class End {
  }

  public static class Consumer {
  }
}
