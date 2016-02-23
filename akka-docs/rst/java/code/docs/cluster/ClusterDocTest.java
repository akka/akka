/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.cluster;

import com.typesafe.config.ConfigFactory;
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.cluster.Cluster;
import akka.testkit.JavaTestKit;


public class ClusterDocTest extends AbstractJavaTest {
  
  static ActorSystem system;
  
  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("ClusterDocTest", 
        ConfigFactory.parseString(ClusterDocSpec.config()));
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void demonstrateLeave() {
    //#leave
    final Cluster cluster = Cluster.get(system);
    cluster.leave(cluster.selfAddress());
    //#leave

  }

}
