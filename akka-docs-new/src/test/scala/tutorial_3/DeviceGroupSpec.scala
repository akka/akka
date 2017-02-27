/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package tutorial_3

import akka.actor.PoisonPill
import akka.testkit.{ AkkaSpec, TestProbe }

import scala.concurrent.duration._

class DeviceGroupSpec extends AkkaSpec {

  "DeviceGroup actor" must {

    "be able to register a device actor" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device1"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor1 = probe.lastSender

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device2"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor2 = probe.lastSender

      // Check that the device actors are working
      probe.send(deviceActor1, Device.RecordTemperature(requestId = 0, 1.0))
      probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
      probe.send(deviceActor2, Device.RecordTemperature(requestId = 1, 2.0))
      probe.expectMsg(Device.TemperatureRecorded(requestId = 1))
    }

    "ignore requests for wrong groupId" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      probe.send(groupActor, DeviceManager.RequestTrackDevice("wrongGroup", "device1"))
      probe.expectNoMsg(500.milliseconds)
    }

    "return same actor for same deviceId" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device1"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor1 = probe.lastSender

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device1"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor2 = probe.lastSender

      deviceActor1 should ===(deviceActor2)
    }

    "be able to list active devices" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device1"))
      probe.expectMsg(DeviceManager.DeviceRegistered)

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device2"))
      probe.expectMsg(DeviceManager.DeviceRegistered)

      probe.send(groupActor, DeviceGroup.RequestDeviceList(requestId = 0))
      probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 0, Set("device1", "device2")))
    }

    "be able to list active devices after one shuts down" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device1"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val toShutDown = probe.lastSender

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device2"))
      probe.expectMsg(DeviceManager.DeviceRegistered)

      probe.send(groupActor, DeviceGroup.RequestDeviceList(requestId = 0))
      probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 0, Set("device1", "device2")))

      probe.watch(toShutDown)
      toShutDown ! PoisonPill
      probe.expectTerminated(toShutDown)

      probe.send(groupActor, DeviceGroup.RequestDeviceList(requestId = 0))
      probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 0, Set("device2")))
    }

    "be able to collect temperatures from all active devices" in {
      val probe = TestProbe()
      val groupActor = system.actorOf(DeviceGroup.props("group"))

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device1"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor1 = probe.lastSender

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device2"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor2 = probe.lastSender

      probe.send(groupActor, DeviceManager.RequestTrackDevice("group", "device3"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      val deviceActor3 = probe.lastSender

      // Check that the device actors are working
      probe.send(deviceActor1, Device.RecordTemperature(requestId = 0, 1.0))
      probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
      probe.send(deviceActor2, Device.RecordTemperature(requestId = 1, 2.0))
      probe.expectMsg(Device.TemperatureRecorded(requestId = 1))
      // No temperature for device3

      probe.send(groupActor, DeviceGroup.RequestAllTemperatures(requestId = 0))
      probe.expectMsg(
        DeviceGroup.RespondAllTemperatures(
          requestId = 0,
          temperatures = Map(
            "device1" -> Some(1.0),
            "device2" -> Some(2.0),
            "device3" -> None
          )
        )
      )
    }

  }

}
