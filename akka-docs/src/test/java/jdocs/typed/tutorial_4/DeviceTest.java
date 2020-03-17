/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_4;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class DeviceTest extends JUnitSuite {

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  // #device-read-test
  @Test
  public void testReplyWithEmptyReadingIfNoTemperatureIsKnown() {
    TestProbe<Device.RespondTemperature> probe =
        testKit.createTestProbe(Device.RespondTemperature.class);
    ActorRef<Device.Command> deviceActor = testKit.spawn(Device.create("group", "device"));
    deviceActor.tell(new Device.ReadTemperature(42L, probe.getRef()));
    Device.RespondTemperature response = probe.receiveMessage();
    assertEquals(42L, response.requestId);
    assertEquals(Optional.empty(), response.value);
  }
  // #device-read-test

  // #device-write-read-test
  @Test
  public void testReplyWithLatestTemperatureReading() {
    TestProbe<Device.TemperatureRecorded> recordProbe =
        testKit.createTestProbe(Device.TemperatureRecorded.class);
    TestProbe<Device.RespondTemperature> readProbe =
        testKit.createTestProbe(Device.RespondTemperature.class);
    ActorRef<Device.Command> deviceActor = testKit.spawn(Device.create("group", "device"));

    deviceActor.tell(new Device.RecordTemperature(1L, 24.0, recordProbe.getRef()));
    assertEquals(1L, recordProbe.receiveMessage().requestId);

    deviceActor.tell(new Device.ReadTemperature(2L, readProbe.getRef()));
    Device.RespondTemperature response1 = readProbe.receiveMessage();
    assertEquals(2L, response1.requestId);
    assertEquals(Optional.of(24.0), response1.value);

    deviceActor.tell(new Device.RecordTemperature(3L, 55.0, recordProbe.getRef()));
    assertEquals(3L, recordProbe.receiveMessage().requestId);

    deviceActor.tell(new Device.ReadTemperature(4L, readProbe.getRef()));
    Device.RespondTemperature response2 = readProbe.receiveMessage();
    assertEquals(4L, response2.requestId);
    assertEquals(Optional.of(55.0), response2.value);
  }
  // #device-write-read-test

}
