/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.tutorial_5;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.testkit.javadsl.TestKit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.FiniteDuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class DeviceGroupQueryTest extends JUnitSuite {

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

  //#query-test-normal
  @Test
  public void testReturnTemperatureValueForWorkingDevices() {
    TestKit requester = new TestKit(system);

    TestKit device1 = new TestKit(system);
    TestKit device2 = new TestKit(system);

    Map<ActorRef, String> actorToDeviceId = new HashMap<>();
    actorToDeviceId.put(device1.getRef(), "device1");
    actorToDeviceId.put(device2.getRef(), "device2");

    ActorRef queryActor = system.actorOf(DeviceGroupQuery.props(
            actorToDeviceId,
            1L,
            requester.getRef(),
            new FiniteDuration(3, TimeUnit.SECONDS)));

    assertEquals(0L, device1.expectMsgClass(Device.ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMsgClass(Device.ReadTemperature.class).requestId);

    queryActor.tell(new Device.RespondTemperature(0L, Optional.of(1.0)), device1.getRef());
    queryActor.tell(new Device.RespondTemperature(0L, Optional.of(2.0)), device2.getRef());

    DeviceGroup.RespondAllTemperatures response = requester.expectMsgClass(DeviceGroup.RespondAllTemperatures.class);
    assertEquals(1L, response.requestId);

    Map<String, DeviceGroup.TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new DeviceGroup.Temperature(1.0));
    expectedTemperatures.put("device2", new DeviceGroup.Temperature(2.0));

    assertEqualTemperatures(expectedTemperatures, response.temperatures);
  }
  //#query-test-normal

  //#query-test-no-reading
  @Test
  public void testReturnTemperatureNotAvailableForDevicesWithNoReadings() {
    TestKit requester = new TestKit(system);

    TestKit device1 = new TestKit(system);
    TestKit device2 = new TestKit(system);

    Map<ActorRef, String> actorToDeviceId = new HashMap<>();
    actorToDeviceId.put(device1.getRef(), "device1");
    actorToDeviceId.put(device2.getRef(), "device2");

    ActorRef queryActor = system.actorOf(DeviceGroupQuery.props(
            actorToDeviceId,
            1L,
            requester.getRef(),
            new FiniteDuration(3, TimeUnit.SECONDS)));

    assertEquals(0L, device1.expectMsgClass(Device.ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMsgClass(Device.ReadTemperature.class).requestId);

    queryActor.tell(new Device.RespondTemperature(0L, Optional.empty()), device1.getRef());
    queryActor.tell(new Device.RespondTemperature(0L, Optional.of(2.0)), device2.getRef());

    DeviceGroup.RespondAllTemperatures response = requester.expectMsgClass(DeviceGroup.RespondAllTemperatures.class);
    assertEquals(1L, response.requestId);

    Map<String, DeviceGroup.TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new DeviceGroup.TemperatureNotAvailable());
    expectedTemperatures.put("device2", new DeviceGroup.Temperature(2.0));

    assertEqualTemperatures(expectedTemperatures, response.temperatures);
  }
  //#query-test-no-reading

  //#query-test-stopped
  @Test
  public void testReturnDeviceNotAvailableIfDeviceStopsBeforeAnswering() {
    TestKit requester = new TestKit(system);

    TestKit device1 = new TestKit(system);
    TestKit device2 = new TestKit(system);

    Map<ActorRef, String> actorToDeviceId = new HashMap<>();
    actorToDeviceId.put(device1.getRef(), "device1");
    actorToDeviceId.put(device2.getRef(), "device2");

    ActorRef queryActor = system.actorOf(DeviceGroupQuery.props(
            actorToDeviceId,
            1L,
            requester.getRef(),
            new FiniteDuration(3, TimeUnit.SECONDS)));

    assertEquals(0L, device1.expectMsgClass(Device.ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMsgClass(Device.ReadTemperature.class).requestId);

    queryActor.tell(new Device.RespondTemperature(0L, Optional.of(1.0)), device1.getRef());
    device2.getRef().tell(PoisonPill.getInstance(), ActorRef.noSender());

    DeviceGroup.RespondAllTemperatures response = requester.expectMsgClass(DeviceGroup.RespondAllTemperatures.class);
    assertEquals(1L, response.requestId);

    Map<String, DeviceGroup.TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new DeviceGroup.Temperature(1.0));
    expectedTemperatures.put("device2", new DeviceGroup.DeviceNotAvailable());

    assertEqualTemperatures(expectedTemperatures, response.temperatures);
  }
  //#query-test-stopped

  //#query-test-stopped-later
  @Test
  public void testReturnTemperatureReadingEvenIfDeviceStopsAfterAnswering() {
    TestKit requester = new TestKit(system);

    TestKit device1 = new TestKit(system);
    TestKit device2 = new TestKit(system);

    Map<ActorRef, String> actorToDeviceId = new HashMap<>();
    actorToDeviceId.put(device1.getRef(), "device1");
    actorToDeviceId.put(device2.getRef(), "device2");

    ActorRef queryActor = system.actorOf(DeviceGroupQuery.props(
            actorToDeviceId,
            1L,
            requester.getRef(),
            new FiniteDuration(3, TimeUnit.SECONDS)));

    assertEquals(0L, device1.expectMsgClass(Device.ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMsgClass(Device.ReadTemperature.class).requestId);

    queryActor.tell(new Device.RespondTemperature(0L, Optional.of(1.0)), device1.getRef());
    queryActor.tell(new Device.RespondTemperature(0L, Optional.of(2.0)), device2.getRef());
    device2.getRef().tell(PoisonPill.getInstance(), ActorRef.noSender());

    DeviceGroup.RespondAllTemperatures response = requester.expectMsgClass(DeviceGroup.RespondAllTemperatures.class);
    assertEquals(1L, response.requestId);

    Map<String, DeviceGroup.TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new DeviceGroup.Temperature(1.0));
    expectedTemperatures.put("device2", new DeviceGroup.Temperature(2.0));

    assertEqualTemperatures(expectedTemperatures, response.temperatures);
  }
  //#query-test-stopped-later

  //#query-test-timeout
  @Test
  public void testReturnDeviceTimedOutIfDeviceDoesNotAnswerInTime() {
    TestKit requester = new TestKit(system);

    TestKit device1 = new TestKit(system);
    TestKit device2 = new TestKit(system);

    Map<ActorRef, String> actorToDeviceId = new HashMap<>();
    actorToDeviceId.put(device1.getRef(), "device1");
    actorToDeviceId.put(device2.getRef(), "device2");

    ActorRef queryActor = system.actorOf(DeviceGroupQuery.props(
            actorToDeviceId,
            1L,
            requester.getRef(),
            new FiniteDuration(3, TimeUnit.SECONDS)));

    assertEquals(0L, device1.expectMsgClass(Device.ReadTemperature.class).requestId);
    assertEquals(0L, device2.expectMsgClass(Device.ReadTemperature.class).requestId);

    queryActor.tell(new Device.RespondTemperature(0L, Optional.of(1.0)), device1.getRef());

    DeviceGroup.RespondAllTemperatures response = requester.expectMsgClass(
            FiniteDuration.create(5, TimeUnit.SECONDS),
            DeviceGroup.RespondAllTemperatures.class);
    assertEquals(1L, response.requestId);

    Map<String, DeviceGroup.TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new DeviceGroup.Temperature(1.0));
    expectedTemperatures.put("device2", new DeviceGroup.DeviceTimedOut());

    assertEqualTemperatures(expectedTemperatures, response.temperatures);
  }
  //#query-test-timeout

  public static void assertEqualTemperatures(Map<String, DeviceGroup.TemperatureReading> expected, Map<String, DeviceGroup.TemperatureReading> actual) {
    for (Map.Entry<String, DeviceGroup.TemperatureReading> entry : expected.entrySet()) {
      DeviceGroup.TemperatureReading expectedReading = entry.getValue();
      DeviceGroup.TemperatureReading actualReading = actual.get(entry.getKey());
      assertNotNull(actualReading);
      assertEquals(expectedReading.getClass(), actualReading.getClass());
      if (expectedReading instanceof DeviceGroup.Temperature) {
        assertEquals(((DeviceGroup.Temperature) expectedReading).value, ((DeviceGroup.Temperature) actualReading).value, 0.01);
      }
    }
    assertEquals(expected.size(), actual.size());
  }
}
