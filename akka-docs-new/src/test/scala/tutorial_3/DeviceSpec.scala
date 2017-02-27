/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_3

import akka.testkit.{ AkkaSpec, TestProbe }

import scala.concurrent.duration._

class DeviceSpec extends AkkaSpec {

  "Device actor" must {

    "reply to registration requests" in {
      val probe = TestProbe()
      val deviceActor = system.actorOf(Device.props("group", "device"))

      probe.send(deviceActor, DeviceManager.RequestTrackDevice("group", "device"))
      probe.expectMsg(DeviceManager.DeviceRegistered)
      probe.lastSender should ===(deviceActor)
    }

    "ignore wrong registration requests" in {
      val probe = TestProbe()
      val deviceActor = system.actorOf(Device.props("group", "device"))

      probe.send(deviceActor, DeviceManager.RequestTrackDevice("wrongGroup", "device"))
      probe.expectNoMsg(500.milliseconds)

      probe.send(deviceActor, DeviceManager.RequestTrackDevice("group", "wrongDevice"))
      probe.expectNoMsg(500.milliseconds)
    }

    "reply with empty reading if no temperature is known" in {
      val probe = TestProbe()
      val deviceActor = system.actorOf(Device.props("group", "device"))

      probe.send(deviceActor, Device.ReadTemperature(requestId = 42))
      val response = probe.expectMsgType[Device.RespondTemperature]
      response.requestId should ===(42)
      response.value should ===(None)
    }

    "reply with latest temperature reading" in {
      val probe = TestProbe()
      val deviceActor = system.actorOf(Device.props("group", "device"))

      probe.send(deviceActor, Device.RecordTemperature(requestId = 1, 24.0))
      probe.expectMsg(Device.TemperatureRecorded(requestId = 1))

      probe.send(deviceActor, Device.ReadTemperature(requestId = 2))
      val response1 = probe.expectMsgType[Device.RespondTemperature]
      response1.requestId should ===(2)
      response1.value should ===(Some(24.0))

      probe.send(deviceActor, Device.RecordTemperature(requestId = 3, 55.0))
      probe.expectMsg(Device.TemperatureRecorded(requestId = 3))

      probe.send(deviceActor, Device.ReadTemperature(requestId = 4))
      val response2 = probe.expectMsgType[Device.RespondTemperature]
      response2.requestId should ===(4)
      response2.value should ===(Some(55.0))
    }

  }

}
