/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.jrouting;

import akka.routing.FromConfig;
import akka.routing.RoundRobinRoutingLogic;
import akka.routing.Routee;
import akka.routing.RoutingLogic;
import akka.routing.SeveralRoutees;
import akka.testkit.AkkaJUnitActorSystemResource;

import docs.AbstractJavaTest;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

import com.typesafe.config.ConfigFactory;

import scala.collection.immutable.IndexedSeq;
import static akka.japi.Util.immutableIndexedSeq;
import docs.jrouting.RouterDocTest.Parent;
import docs.jrouting.RouterDocTest.Workers;
import docs.routing.CustomRouterDocSpec;
import akka.testkit.JavaTestKit;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
//#imports1
import akka.actor.UntypedActor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

//#imports1


public class CustomRouterDocTest extends AbstractJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("CustomRouterDocTest", 
        ConfigFactory.parseString(CustomRouterDocSpec.jconfig()));

  private final ActorSystem system = actorSystemResource.getSystem();

  static
  //#routing-logic
  public class RedundancyRoutingLogic implements RoutingLogic {
    private final int nbrCopies;
    
    public RedundancyRoutingLogic(int nbrCopies) {
      this.nbrCopies = nbrCopies;  
    }
    RoundRobinRoutingLogic roundRobin = new RoundRobinRoutingLogic();
    
    @Override
    public Routee select(Object message, IndexedSeq<Routee> routees) {
      List<Routee> targets = new ArrayList<Routee>();
      for (int i = 0; i < nbrCopies; i++) {
        targets.add(roundRobin.select(message, routees));
      }
      return new SeveralRoutees(targets);
    }
  }
  //#routing-logic
  
  static 
  //#unit-test-logic
  public final class TestRoutee implements Routee {
    public final int n;

    public TestRoutee(int n) {
      this.n = n;
    }

    @Override
    public void send(Object message, ActorRef sender) {
    }

    @Override
    public int hashCode() {
      return n;
    }

    @Override
    public boolean equals(Object obj) {
      return (obj instanceof TestRoutee) && 
        n == ((TestRoutee) obj).n;
    }
  }

  //#unit-test-logic
  
  static public class Storage extends UntypedActor {
    public void onReceive(Object msg) {
      getSender().tell(msg, getSelf());
    }
  }
  
  @Test
  public void unitTestRoutingLogic() {
    //#unit-test-logic
    RedundancyRoutingLogic logic = new RedundancyRoutingLogic(3);

    List<Routee> routeeList = new ArrayList<Routee>();
    for (int n = 1; n <= 7; n++) {
      routeeList.add(new TestRoutee(n));
    }
    IndexedSeq<Routee> routees = immutableIndexedSeq(routeeList);

    SeveralRoutees r1 = (SeveralRoutees) logic.select("msg", routees);
    assertEquals(r1.getRoutees().get(0), routeeList.get(0));
    assertEquals(r1.getRoutees().get(1), routeeList.get(1));
    assertEquals(r1.getRoutees().get(2), routeeList.get(2));
    
    SeveralRoutees r2 = (SeveralRoutees) logic.select("msg", routees);
    assertEquals(r2.getRoutees().get(0), routeeList.get(3));
    assertEquals(r2.getRoutees().get(1), routeeList.get(4));
    assertEquals(r2.getRoutees().get(2), routeeList.get(5));

    SeveralRoutees r3 = (SeveralRoutees) logic.select("msg", routees);
    assertEquals(r3.getRoutees().get(0), routeeList.get(6));
    assertEquals(r3.getRoutees().get(1), routeeList.get(0));
    assertEquals(r3.getRoutees().get(2), routeeList.get(1));

    //#unit-test-logic
  }

  @Test
  public void demonstrateUsageOfCustomRouter() {
    new JavaTestKit(system) {{
      //#usage-1
      for (int n = 1; n <= 10; n++) {
        system.actorOf(Props.create(Storage.class), "s" + n);
      }

      List<String> paths = new ArrayList<String>();
      for (int n = 1; n <= 10; n++) {
        paths.add("/user/s" + n);
      }

      ActorRef redundancy1 =
        system.actorOf(new RedundancyGroup(paths, 3).props(),
          "redundancy1");
      redundancy1.tell("important", getTestActor());
      //#usage-1

      for (int i = 0; i < 3; i++) {
        expectMsgEquals("important");
      }

      //#usage-2
      ActorRef redundancy2 = system.actorOf(FromConfig.getInstance().props(),
        "redundancy2");
      redundancy2.tell("very important", getTestActor());
      //#usage-2

      for (int i = 0; i < 5; i++) {
        expectMsgEquals("very important");
      }
    }};
  }

}
