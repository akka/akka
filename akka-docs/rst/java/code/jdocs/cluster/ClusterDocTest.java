/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.cluster;

import akka.testkit.javadsl.TestKit;
import com.typesafe.config.ConfigFactory;
import jdocs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.cluster.Cluster;


public class ClusterDocTest extends AbstractJavaTest {
  
  static ActorSystem system;
  
  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("ClusterDocTest", 
        ConfigFactory.parseString(scala.docs.cluster.ClusterDocSpec.config()));
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
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
