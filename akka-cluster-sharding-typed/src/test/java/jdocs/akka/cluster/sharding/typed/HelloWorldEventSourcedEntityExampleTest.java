/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Join;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import static jdocs.akka.cluster.sharding.typed.HelloWorldPersistentEntityExample.*;
import static org.junit.Assert.assertEquals;

public class HelloWorldEventSourcedEntityExampleTest extends JUnitSuite {

  public static final Config config = ConfigFactory.parseString(
          "akka.actor.provider = cluster \n" +
          "akka.remote.netty.tcp.port = 0 \n" +
          "akka.remote.artery.canonical.port = 0 \n" +
          "akka.remote.artery.canonical.hostname = 127.0.0.1 \n" +
          "akka.persistence.journal.plugin = \"akka.persistence.journal.inmem\" \n");

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  private ClusterSharding _sharding = null;

  private ClusterSharding sharding() {
    if (_sharding == null) {
      // initialize first time only
      Cluster cluster = Cluster.get(testKit.system());
      cluster.manager().tell(new Join(cluster.selfMember().address()));

      ClusterSharding sharding = ClusterSharding.get(testKit.system());
      sharding.init(
        Entity.ofPersistentEntity(
          HelloWorld.ENTITY_TYPE_KEY,
          ctx -> new HelloWorld(ctx.getActorContext(), ctx.getEntityId())));
      _sharding = sharding;
    }
    return _sharding;
  }

  @Test
  public void sayHello() {
    EntityRef<HelloWorld.Command> world = sharding().entityRefFor(HelloWorld.ENTITY_TYPE_KEY, "1");
    TestProbe<HelloWorld.Greeting> probe = testKit.createTestProbe(HelloWorld.Greeting.class);
    world.tell(new HelloWorld.Greet("Alice", probe.getRef()));
    HelloWorld.Greeting greeting1 = probe.expectMessageClass(HelloWorld.Greeting.class);
    assertEquals("Alice", greeting1.whom);
    assertEquals(1, greeting1.numberOfPeople);

    world.tell(new HelloWorld.Greet("Bob", probe.getRef()));
    HelloWorld.Greeting greeting2 = probe.expectMessageClass(HelloWorld.Greeting.class);
    assertEquals("Bob", greeting2.whom);
    assertEquals(2, greeting2.numberOfPeople);
  }

}
