/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_3;

// #device-read-test
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Test;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

// #device-read-test
import static jdocs.typed.tutorial_3.DeviceProtocol.*;
/*
//#device-read-test
import static com.lightbend.akka.sample.DeviceProtocol.*;

public class DeviceTest {
//#device-read-test
*/
public class DeviceTest extends org.scalatestplus.junit.JUnitSuite {
  // #device-read-test

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void testReplyWithEmptyReadingIfNoTemperatureIsKnown() {
    TestProbe<RespondTemperature> probe = testKit.createTestProbe(RespondTemperature.class);
    ActorRef<DeviceMessage> deviceActor = testKit.spawn(Device.createBehavior("group", "device"));
    deviceActor.tell(new ReadTemperature(42L, probe.getRef()));
    RespondTemperature response = probe.receiveMessage();
    assertEquals(42L, response.requestId);
    assertEquals(Optional.empty(), response.value);
  }
  // #device-read-test

  // #device-write-read-test
  @Test
  public void testReplyWithLatestTemperatureReading() {
    TestProbe<TemperatureRecorded> recordProbe = testKit.createTestProbe(TemperatureRecorded.class);
    TestProbe<RespondTemperature> readProbe = testKit.createTestProbe(RespondTemperature.class);
    ActorRef<DeviceMessage> deviceActor = testKit.spawn(Device.createBehavior("group", "device"));

    deviceActor.tell(new RecordTemperature(1L, 24.0, recordProbe.getRef()));
    assertEquals(1L, recordProbe.receiveMessage().requestId);

    deviceActor.tell(new ReadTemperature(2L, readProbe.getRef()));
    RespondTemperature response1 = readProbe.receiveMessage();
    assertEquals(2L, response1.requestId);
    assertEquals(Optional.of(24.0), response1.value);

    deviceActor.tell(new RecordTemperature(3L, 55.0, recordProbe.getRef()));
    assertEquals(3L, recordProbe.receiveMessage().requestId);

    deviceActor.tell(new ReadTemperature(4L, readProbe.getRef()));
    RespondTemperature response2 = readProbe.receiveMessage();
    assertEquals(4L, response2.requestId);
    assertEquals(Optional.of(55.0), response2.value);
  }
  // #device-write-read-test

  // #device-read-test
}
// #device-read-test
