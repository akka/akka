/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package tutorial_4

import akka.actor.PoisonPill
import akka.testkit.{ AkkaSpec, TestProbe }

import scala.concurrent.duration._

class DeviceGroupSpec extends AkkaSpec {

  "DeviceGroup actor" must {

    //#device-group-test-registration
    "be able to register a device actor" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor1 = probe.lastSender

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device2"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor2 = probe.lastSender
      deviceActor1 should !==(deviceActor2)

      // Check that the device actors are working
      deviceActor1.tell(Device.RecordTemperature(requestId = 0, 1.0), probe.ref)
      probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
      deviceActor2.tell(Device.RecordTemperature(requestId = 1, 2.0), probe.ref)
      probe.expectMsg(Device.TemperatureRecorded(requestId = 1))
    }

    "ignore requests for wrong groupId" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("wrongGroup", "device1"), probe.ref)
      probe.expectNoMsg(500.milliseconds)
    }
    //#device-group-test-registration

    //#device-group-test3
    "return same actor for same deviceId" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor1 = probe.lastSender

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor2 = probe.lastSender

      deviceActor1 should ===(deviceActor2)
    }
    //#device-group-test3

    //#device-group-list-terminate-test
    "be able to list active devices" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device2"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)

      groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 0), probe.ref)
      probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 0, Set("device1", "device2")))
    }

    "be able to list active devices after one shuts down" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device1"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val toShutDown = probe.lastSender

      groupActor.tell(DeviceManager.RequestTrackDevice("group", "device2"), probe.ref)
      probe.expectMsg(DeviceManager.DeviceRegistered)

      groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 0), probe.ref)
      probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 0, Set("device1", "device2")))

      probe.watch(toShutDown)
      toShutDown ! PoisonPill
      probe.expectTerminated(toShutDown)

      // using awaitAssert to retry because it might take longer for the groupActor
      // to see the Terminated, that order is undefined
      probe.awaitAssert {
        groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 1), probe.ref)
        probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 1, Set("device2")))
      }
    }
    //#device-group-list-terminate-test

  }

}
