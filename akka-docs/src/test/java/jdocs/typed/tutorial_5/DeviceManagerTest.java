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

import static jdocs.typed.tutorial_5.DeviceManagerProtocol.*;
import static org.junit.Assert.assertNotEquals;

public class DeviceManagerTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void testReplyToRegistrationRequests() {
    TestProbe<DeviceRegistered> probe = testKit.createTestProbe(DeviceRegistered.class);
    ActorRef<DeviceManagerMessage> managerActor = testKit.spawn(DeviceManager.createBehavior());

    managerActor.tell(new RequestTrackDevice("group1", "device", probe.getRef()));
    DeviceRegistered registered1 = probe.expectMessageClass(DeviceRegistered.class);

    // another group
    managerActor.tell(new RequestTrackDevice("group2", "device", probe.getRef()));
    DeviceRegistered registered2 = probe.expectMessageClass(DeviceRegistered.class);
    assertNotEquals(registered1.device, registered2.device);
  }

}
