/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actor;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class SampleActorTest extends AbstractJavaTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("SampleActorTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void testSampleActor()
  {
    new JavaTestKit(system) {{
      final ActorRef subject = system.actorOf(Props.create(SampleActor.class), "sample-actor");
      final ActorRef probeRef = getRef();

      subject.tell(47.11, probeRef);
      subject.tell("and no guard in the beginning", probeRef);
      subject.tell("guard is a good thing", probeRef);
      subject.tell(47.11, probeRef);
      subject.tell(4711, probeRef);
      subject.tell("and no guard in the beginning", probeRef);
      subject.tell(4711, probeRef);
      subject.tell("and an unmatched message", probeRef);

      expectMsgEquals(47.11);
      assertTrue(expectMsgClass(String.class).startsWith("startsWith(guard):"));
      assertTrue(expectMsgClass(String.class).startsWith("contains(guard):"));
      expectMsgEquals(47110);
      expectNoMsg();
    }};
  }
}
