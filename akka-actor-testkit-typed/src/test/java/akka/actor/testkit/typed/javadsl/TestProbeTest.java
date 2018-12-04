/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.javadsl;

import java.time.Duration;
import java.util.List;

import akka.actor.testkit.typed.scaladsl.TestProbeSpec;
import akka.actor.testkit.typed.scaladsl.TestProbeSpec.*;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import static org.junit.Assert.*;

public class TestProbeTest extends JUnitSuite {

  @ClassRule
  public static TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void testReceiveOne() {
    TestProbe<EventT> probe = TestProbe.create(testKit.system());

    List<EventT> eventsT = akka.japi.Util.javaArrayList(TestProbeSpec.eventsT(10));

    eventsT.forEach(e->{
      probe.getRef().tell(e);
      assertEquals(probe.receiveOne(), e);
    });

    probe.expectNoMessage();
  }

  @Test
  public void testReceiveOneMaxDuration() {
    TestProbe<EventT> probe = TestProbe.create(testKit.system());

    List<EventT> eventsT = akka.japi.Util.javaArrayList(TestProbeSpec.eventsT(2));

    eventsT.forEach(e->{
      probe.getRef().tell(e);
      assertEquals(probe.receiveOne(Duration.ofMillis(100)), e);
    });

    probe.expectNoMessage();
  }

  @Test(expected = AssertionError.class)
  public void testReceiveOneFailOnTimeout() {
    TestProbe<EventT> probe = TestProbe.create(testKit.system());
    probe.receiveOne(Duration.ofMillis(100));
  }

}
