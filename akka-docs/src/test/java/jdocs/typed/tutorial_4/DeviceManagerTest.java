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

import static org.junit.Assert.assertNotEquals;

import static jdocs.typed.tutorial_4.DeviceManager.DeviceRegistered;
import static jdocs.typed.tutorial_4.DeviceManager.RequestTrackDevice;

public class DeviceManagerTest extends JUnitSuite {

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void testReplyToRegistrationRequests() {
    TestProbe<DeviceRegistered> probe = testKit.createTestProbe(DeviceRegistered.class);
    ActorRef<DeviceManager.Command> managerActor = testKit.spawn(DeviceManager.create());

    managerActor.tell(new RequestTrackDevice("group1", "device", probe.getRef()));
    DeviceRegistered registered1 = probe.receiveMessage();

    // another group
    managerActor.tell(new RequestTrackDevice("group2", "device", probe.getRef()));
    DeviceRegistered registered2 = probe.receiveMessage();
    assertNotEquals(registered1.device, registered2.device);
  }
}
