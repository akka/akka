/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.remoting;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

//#import
import akka.actor.ActorRef;
import akka.actor.Address;
import akka.actor.AddressExtractor;
import akka.actor.Deploy;
import akka.actor.Props;
import akka.actor.ActorSystem;
import akka.remote.RemoteScope;
//#import

public class RemoteDeploymentDocTestBase {
  
  static ActorSystem system;
  
  @BeforeClass
  public static void init() {
    system = ActorSystem.create();
  }
  
  @AfterClass
  public static void cleanup() {
    system.shutdown();
  }
  
  @Test
  public void demonstrateDeployment() {
    //#make-address
    Address addr = new Address("akka", "sys", "host", 1234);
    addr = AddressExtractor.parse("akka://sys@host:1234"); // the same
    //#make-address
    //#deploy
    ActorRef ref = system.actorOf(new Props(RemoteDeploymentDocSpec.Echo.class).withDeploy(new Deploy(new RemoteScope(addr))));
    //#deploy
    assert ref.path().address().equals(addr);
  }
  
}