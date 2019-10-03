/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.ddata.typed.javadsl;

import akka.actor.testkit.typed.javadsl.LogCapturing;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.cluster.ddata.GCounter;
import akka.cluster.ddata.GCounterKey;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.typed.javadsl.DistributedData;
import akka.cluster.ddata.typed.javadsl.Replicator;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import static jdocs.akka.cluster.ddata.typed.javadsl.ReplicatorDocSample.Counter;
import static org.junit.Assert.assertEquals;

public class ReplicatorDocTest extends JUnitSuite {

  static Config config =
      ConfigFactory.parseString(
          "akka.actor.provider = cluster \n"
              + "akka.remote.classic.netty.tcp.port = 0 \n"
              + "akka.remote.artery.canonical.port = 0 \n"
              + "akka.remote.artery.canonical.hostname = 127.0.0.1 \n");

  @ClassRule public static TestKitJunitResource testKit = new TestKitJunitResource(config);

  @Rule public final LogCapturing logCapturing = new LogCapturing();

  @Test
  public void shouldHaveApiForUpdateAndGet() {
    TestProbe<Integer> probe = testKit.createTestProbe(Integer.class);
    ActorRef<Counter.Command> client =
        testKit.spawn(Counter.create(GCounterKey.create("counter1")));

    client.tell(Counter.Increment.INSTANCE);
    client.tell(new Counter.GetValue(probe.getRef()));
    probe.expectMessage(1);
  }

  @Test
  public void shouldHaveApiForSubscribe() {
    TestProbe<Integer> probe = testKit.createTestProbe(Integer.class);
    ActorRef<Counter.Command> client =
        testKit.spawn(Counter.create(GCounterKey.create("counter2")));

    client.tell(Counter.Increment.INSTANCE);
    client.tell(Counter.Increment.INSTANCE);
    probe.awaitAssert(
        () -> {
          client.tell(new Counter.GetCachedValue(probe.getRef()));
          probe.expectMessage(2);
          return null;
        });

    client.tell(Counter.Increment.INSTANCE);
    probe.awaitAssert(
        () -> {
          client.tell(new Counter.GetCachedValue(probe.getRef()));
          probe.expectMessage(3);
          return null;
        });
  }

  @Test
  public void shouldHaveAnExtension() {
    Key<GCounter> key = GCounterKey.create("counter3");
    ActorRef<Counter.Command> client = testKit.spawn(Counter.create(key));

    TestProbe<Integer> probe = testKit.createTestProbe(Integer.class);
    client.tell(Counter.Increment.INSTANCE);
    client.tell(new Counter.GetValue(probe.getRef()));
    probe.expectMessage(1);

    TestProbe<Replicator.GetResponse<GCounter>> getReplyProbe = testKit.createTestProbe();
    ActorRef<Replicator.Command> replicator = DistributedData.get(testKit.system()).replicator();
    replicator.tell(new Replicator.Get<>(key, Replicator.readLocal(), getReplyProbe.getRef()));
    @SuppressWarnings("unchecked")
    Replicator.GetSuccess<GCounter> rsp =
        getReplyProbe.expectMessageClass(Replicator.GetSuccess.class);
    assertEquals(1, rsp.get(key).getValue().intValue());
  }
}
