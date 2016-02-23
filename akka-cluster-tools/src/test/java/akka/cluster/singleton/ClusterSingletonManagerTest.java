/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.singleton;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Props;

public class ClusterSingletonManagerTest {

  @SuppressWarnings("null")
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
