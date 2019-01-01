/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed;

import akka.cluster.ClusterEvent;
import akka.actor.typed.ActorSystem;
import akka.actor.testkit.typed.javadsl.TestProbe;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

public class ClusterApiTest extends JUnitSuite {

  @Test
  public void joinLeaveAndObserve() throws Exception {
    Config config = ConfigFactory.parseString(
        "akka.actor.provider = cluster \n" +
        "akka.remote.netty.tcp.port = 0 \n"+
        "akka.remote.artery.canonical.port = 0 \n"+
        "akka.remote.artery.canonical.hostname = 127.0.0.1 \n" +
        "akka.cluster.jmx.multi-mbeans-in-same-jvm = on \n"+
        "akka.coordinated-shutdown.terminate-actor-system = off \n"+
        "akka.actor { \n"+
        "  serialize-messages = off \n"+
        "  allow-java-serialization = off \n"+
        "}"
    );

    ActorSystem<?> system1 = ActorSystem.wrap(akka.actor.ActorSystem.create("ClusterApiTest", config));
    ActorSystem<?> system2 = ActorSystem.wrap(akka.actor.ActorSystem.create("ClusterApiTest", config));

    try {
      Cluster cluster1 = Cluster.get(system1);
      Cluster cluster2 = Cluster.get(system2);

      TestProbe<ClusterEvent.ClusterDomainEvent> probe1 = TestProbe.create(system1);

      cluster1.subscriptions().tell(new Subscribe<>(probe1.ref().narrow(), SelfUp.class));
      cluster1.manager().tell(new Join(cluster1.selfMember().address()));
      probe1.expectMessageClass(SelfUp.class);

      TestProbe<ClusterEvent.ClusterDomainEvent> probe2 = TestProbe.create(system2);
      cluster2.subscriptions().tell(new Subscribe<>(probe2.ref().narrow(), SelfUp.class));
      cluster2.manager().tell(new Join(cluster1.selfMember().address()));
      probe2.expectMessageClass(SelfUp.class);


      cluster2.subscriptions().tell(new Subscribe<>(probe2.ref().narrow(), SelfRemoved.class));
      cluster2.manager().tell(new Leave(cluster2.selfMember().address()));

      probe2.expectMessageClass(SelfRemoved.class);
    } finally {
      // TODO no java API to terminate actor system
      Await.result(system1.terminate().zip(system2.terminate()), Duration.create("5 seconds"));
    }

  }

}
