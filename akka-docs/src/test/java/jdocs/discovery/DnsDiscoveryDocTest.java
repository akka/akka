/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.discovery;

import akka.actor.ActorSystem;
// #lookup-dns
import akka.discovery.Discovery;
import akka.discovery.ServiceDiscovery;
// #lookup-dns
import akka.testkit.javadsl.TestKit;
import docs.discovery.DnsDiscoveryDocSpec;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

@SuppressWarnings("unused")
public class DnsDiscoveryDocTest extends JUnitSuite {

  private static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("DnsDiscoveryDocTest", DnsDiscoveryDocSpec.config());
  }

  @AfterClass
  public static void teardown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void dnsDiscoveryShouldResolveAkkaIo() throws Exception {
    // #lookup-dns

    ServiceDiscovery discovery = Discovery.get(system).discovery();
    // ...
    CompletionStage<ServiceDiscovery.Resolved> result =
        discovery.lookup("akka.io", Duration.ofMillis(500));
    // #lookup-dns
  }
}
