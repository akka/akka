/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_5;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static jdocs.typed.tutorial_5.DeviceManagerProtocol.*;
import static jdocs.typed.tutorial_5.DeviceProtocol.*;
import static org.junit.Assert.assertEquals;

public class DeviceGroupQueryTest extends JUnitSuite {

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  // #query-test-normal
  @Test
  public void testReturnTemperatureValueForWorkingDevices() {
    TestProbe<RespondAllTemperatures> requester =
        testKit.createTestProbe(RespondAllTemperatures.class);
    TestProbe<DeviceMessage> device1 = testKit.createTestProbe(DeviceMessage.class);
    TestProbe<DeviceMessage> device2 = testKit.createTestProbe(DeviceMessage.class);

    Map<String, ActorRef<DeviceProtocol.DeviceMessage>> deviceIdToActor = new HashMap<>();
    deviceIdToActor.put("device1", device1.getRef());
    deviceIdToActor.put("device2", device2.getRef());

    ActorRef<DeviceGroupQueryMessage> queryActor =
        testKit.spawn(
            DeviceGroupQuery.createBehavior(
                deviceIdToActor, 1L, requester.getRef(), Duration.ofSeconds(3)));

    device1.expectMessageClass(ReadTemperature.class);
    device2.expectMessageClass(ReadTemperature.class);

    queryActor.tell(
        new DeviceGroupQuery.WrappedRespondTemperature(
            new RespondTemperature(0L, "device1", Optional.of(1.0))));

    queryActor.tell(
        new DeviceGroupQuery.WrappedRespondTemperature(
            new RespondTemperature(0L, "device2", Optional.of(2.0))));

    RespondAllTemperatures response = requester.receiveMessage();
    assertEquals(1L, response.requestId);

    Map<String, TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new Temperature(1.0));
    expectedTemperatures.put("device2", new Temperature(2.0));

    assertEquals(expectedTemperatures, response.temperatures);
  }
  // #query-test-normal

  // #query-test-no-reading
  @Test
  public void testReturnTemperatureNotAvailableForDevicesWithNoReadings() {
    TestProbe<RespondAllTemperatures> requester =
        testKit.createTestProbe(RespondAllTemperatures.class);
    TestProbe<DeviceMessage> device1 = testKit.createTestProbe(DeviceMessage.class);
    TestProbe<DeviceMessage> device2 = testKit.createTestProbe(DeviceMessage.class);

    Map<String, ActorRef<DeviceProtocol.DeviceMessage>> deviceIdToActor = new HashMap<>();
    deviceIdToActor.put("device1", device1.getRef());
    deviceIdToActor.put("device2", device2.getRef());

    ActorRef<DeviceGroupQueryMessage> queryActor =
        testKit.spawn(
            DeviceGroupQuery.createBehavior(
                deviceIdToActor, 1L, requester.getRef(), Duration.ofSeconds(3)));

    assertEquals(0L, device1.expectMessageClass(ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMessageClass(ReadTemperature.class).requestId);

    queryActor.tell(
        new DeviceGroupQuery.WrappedRespondTemperature(
            new RespondTemperature(0L, "device1", Optional.empty())));

    queryActor.tell(
        new DeviceGroupQuery.WrappedRespondTemperature(
            new RespondTemperature(0L, "device2", Optional.of(2.0))));

    RespondAllTemperatures response = requester.receiveMessage();
    assertEquals(1L, response.requestId);

    Map<String, TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", TemperatureNotAvailable.INSTANCE);
    expectedTemperatures.put("device2", new Temperature(2.0));

    assertEquals(expectedTemperatures, response.temperatures);
  }
  // #query-test-no-reading

  // #query-test-stopped
  @Test
  public void testReturnDeviceNotAvailableIfDeviceStopsBeforeAnswering() {
    TestProbe<RespondAllTemperatures> requester =
        testKit.createTestProbe(RespondAllTemperatures.class);
    TestProbe<DeviceMessage> device1 = testKit.createTestProbe(DeviceMessage.class);
    TestProbe<DeviceMessage> device2 = testKit.createTestProbe(DeviceMessage.class);

    Map<String, ActorRef<DeviceProtocol.DeviceMessage>> deviceIdToActor = new HashMap<>();
    deviceIdToActor.put("device1", device1.getRef());
    deviceIdToActor.put("device2", device2.getRef());

    ActorRef<DeviceGroupQueryMessage> queryActor =
        testKit.spawn(
            DeviceGroupQuery.createBehavior(
                deviceIdToActor, 1L, requester.getRef(), Duration.ofSeconds(3)));

    assertEquals(0L, device1.expectMessageClass(ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMessageClass(ReadTemperature.class).requestId);

    queryActor.tell(
        new DeviceGroupQuery.WrappedRespondTemperature(
            new RespondTemperature(0L, "device1", Optional.of(1.0))));

    device2.stop();

    RespondAllTemperatures response = requester.receiveMessage();
    assertEquals(1L, response.requestId);

    Map<String, TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new Temperature(1.0));
    expectedTemperatures.put("device2", DeviceNotAvailable.INSTANCE);

    assertEquals(expectedTemperatures, response.temperatures);
  }
  // #query-test-stopped

  // #query-test-stopped-later
  @Test
  public void testReturnTemperatureReadingEvenIfDeviceStopsAfterAnswering() {
    TestProbe<RespondAllTemperatures> requester =
        testKit.createTestProbe(RespondAllTemperatures.class);
    TestProbe<DeviceMessage> device1 = testKit.createTestProbe(DeviceMessage.class);
    TestProbe<DeviceMessage> device2 = testKit.createTestProbe(DeviceMessage.class);

    Map<String, ActorRef<DeviceProtocol.DeviceMessage>> deviceIdToActor = new HashMap<>();
    deviceIdToActor.put("device1", device1.getRef());
    deviceIdToActor.put("device2", device2.getRef());

    ActorRef<DeviceGroupQueryMessage> queryActor =
        testKit.spawn(
            DeviceGroupQuery.createBehavior(
                deviceIdToActor, 1L, requester.getRef(), Duration.ofSeconds(3)));

    assertEquals(0L, device1.expectMessageClass(ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMessageClass(ReadTemperature.class).requestId);

    queryActor.tell(
        new DeviceGroupQuery.WrappedRespondTemperature(
            new RespondTemperature(0L, "device1", Optional.of(1.0))));

    queryActor.tell(
        new DeviceGroupQuery.WrappedRespondTemperature(
            new RespondTemperature(0L, "device2", Optional.of(2.0))));

    device2.stop();

    RespondAllTemperatures response = requester.receiveMessage();
    assertEquals(1L, response.requestId);

    Map<String, TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new Temperature(1.0));
    expectedTemperatures.put("device2", new Temperature(2.0));

    assertEquals(expectedTemperatures, response.temperatures);
  }
  // #query-test-stopped-later

  // #query-test-timeout
  @Test
  public void testReturnDeviceTimedOutIfDeviceDoesNotAnswerInTime() {
    TestProbe<RespondAllTemperatures> requester =
        testKit.createTestProbe(RespondAllTemperatures.class);
    TestProbe<DeviceMessage> device1 = testKit.createTestProbe(DeviceMessage.class);
    TestProbe<DeviceMessage> device2 = testKit.createTestProbe(DeviceMessage.class);

    Map<String, ActorRef<DeviceProtocol.DeviceMessage>> deviceIdToActor = new HashMap<>();
    deviceIdToActor.put("device1", device1.getRef());
    deviceIdToActor.put("device2", device2.getRef());

    ActorRef<DeviceGroupQueryMessage> queryActor =
        testKit.spawn(
            DeviceGroupQuery.createBehavior(
                deviceIdToActor, 1L, requester.getRef(), Duration.ofMillis(200)));

    assertEquals(0L, device1.expectMessageClass(ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMessageClass(ReadTemperature.class).requestId);

    queryActor.tell(
        new DeviceGroupQuery.WrappedRespondTemperature(
            new RespondTemperature(0L, "device1", Optional.of(1.0))));

    // no reply from device2

    RespondAllTemperatures response = requester.receiveMessage();
    assertEquals(1L, response.requestId);

    Map<String, TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new Temperature(1.0));
    expectedTemperatures.put("device2", DeviceTimedOut.INSTANCE);

    assertEquals(expectedTemperatures, response.temperatures);
  }
  // #query-test-timeout
}
