/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.tutorial_2;

import java.util.Optional;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import org.scalatest.junit.JUnitSuite;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.testkit.javadsl.TestKit;

public class DeviceTest extends JUnitSuite {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create();
  }

  @AfterClass
  public static void teardown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  //#device-read-test
  @Test
  public void testReplyWithEmptyReadingIfNoTemperatureIsKnown() {
    TestKit probe = new TestKit(system);
    ActorRef deviceActor = system.actorOf(Device.props("group", "device"));
    deviceActor.tell(new Device.ReadTemperature(42L), probe.getRef());
    Device.RespondTemperature response = probe.expectMsgClass(Device.RespondTemperature.class);
    Assert.assertEquals(response.requestId, (Long) 42L);
    Assert.assertEquals(response.value, Optional.empty());
  }
  //#device-read-test

  //#device-write-read-test
  @Test
  public void testReplyWithLatestTemperatureReading() {
    TestKit probe = new TestKit(system);
    ActorRef deviceActor = system.actorOf(Device.props("group", "device"));

    deviceActor.tell(new Device.RecordTemperature(1L, 24.0), probe.getRef());
    Assert.assertEquals(probe.expectMsgClass(Device.TemperatureRecorded.class).requestId, (Long) 1L);

    deviceActor.tell(new Device.ReadTemperature(2L), probe.getRef());
    Device.RespondTemperature response1 = probe.expectMsgClass(Device.RespondTemperature.class);
    Assert.assertEquals(response1.requestId, (Long) 2L);
    Assert.assertEquals(response1.value, Optional.of(24.0));

    deviceActor.tell(new Device.RecordTemperature(3L, 55.0), probe.getRef());
    Assert.assertEquals(probe.expectMsgClass(Device.TemperatureRecorded.class).requestId, (Long) 3L);

    deviceActor.tell(new Device.ReadTemperature(4L), probe.getRef());
    Device.RespondTemperature response2 = probe.expectMsgClass(Device.RespondTemperature.class);
    Assert.assertEquals(response2.requestId, (Long) 4L);
    Assert.assertEquals(response2.value, Optional.of(55.0));
  }
  //#device-write-read-test

}
