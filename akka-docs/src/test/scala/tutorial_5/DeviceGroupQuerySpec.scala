/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_5

import akka.actor.PoisonPill
import akka.testkit.{ AkkaSpec, TestProbe }

import scala.concurrent.duration._

class DeviceGroupQuerySpec extends AkkaSpec {

  "DeviceGroupQuery" must {

    "return temperature value for working devices" in {
      val requester = TestProbe()

      val device1 = TestProbe()
      val device2 = TestProbe()

      val queryActor = system.actorOf(DeviceGroupQuery.props(
        actorToDeviceId = Map(device1.ref -> "device1", device2.ref -> "device2"),
        requestId = 1,
        requester = requester.ref,
        timeout = 3.seconds
      ))

      device1.expectMsg(Device.ReadTemperature(requestId = 0))
      device2.expectMsg(Device.ReadTemperature(requestId = 0))

      queryActor.tell(Device.RespondTemperature(requestId = 0, Some(1.0)), device1.ref)
      queryActor.tell(Device.RespondTemperature(requestId = 0, Some(2.0)), device2.ref)

      requester.expectMsg(DeviceGroup.RespondAllTemperatures(
        requestId = 1,
        temperatures = Map(
          "device1" -> DeviceGroup.Temperature(1.0),
          "device2" -> DeviceGroup.Temperature(2.0)
        )
      ))
    }

    "return TemperatureNotAvailable for devices with no readings" in {
      val requester = TestProbe()

      val device1 = TestProbe()
      val device2 = TestProbe()

      val queryActor = system.actorOf(DeviceGroupQuery.props(
        actorToDeviceId = Map(device1.ref -> "device1", device2.ref -> "device2"),
        requestId = 1,
        requester = requester.ref,
        timeout = 3.seconds
      ))

      device1.expectMsg(Device.ReadTemperature(requestId = 0))
      device2.expectMsg(Device.ReadTemperature(requestId = 0))

      queryActor.tell(Device.RespondTemperature(requestId = 0, None), device1.ref)
      queryActor.tell(Device.RespondTemperature(requestId = 0, Some(2.0)), device2.ref)

      requester.expectMsg(DeviceGroup.RespondAllTemperatures(
        requestId = 1,
        temperatures = Map(
          "device1" -> DeviceGroup.TemperatureNotAvailable,
          "device2" -> DeviceGroup.Temperature(2.0)
        )
      ))
    }

    "return DeviceNotAvailable if device stops before answering" in {
      val requester = TestProbe()

      val device1 = TestProbe()
      val device2 = TestProbe()

      val queryActor = system.actorOf(DeviceGroupQuery.props(
        actorToDeviceId = Map(device1.ref -> "device1", device2.ref -> "device2"),
        requestId = 1,
        requester = requester.ref,
        timeout = 3.seconds
      ))

      device1.expectMsg(Device.ReadTemperature(requestId = 0))
      device2.expectMsg(Device.ReadTemperature(requestId = 0))

      queryActor.tell(Device.RespondTemperature(requestId = 0, Some(1.0)), device1.ref)
      device2.ref ! PoisonPill

      requester.expectMsg(DeviceGroup.RespondAllTemperatures(
        requestId = 1,
        temperatures = Map(
          "device1" -> DeviceGroup.Temperature(1.0),
          "device2" -> DeviceGroup.DeviceNotAvailable
        )
      ))
    }

    "return temperature reading even if device stops after answering" in {
      val requester = TestProbe()

      val device1 = TestProbe()
      val device2 = TestProbe()

      val queryActor = system.actorOf(DeviceGroupQuery.props(
        actorToDeviceId = Map(device1.ref -> "device1", device2.ref -> "device2"),
        requestId = 1,
        requester = requester.ref,
        timeout = 3.seconds
      ))

      device1.expectMsg(Device.ReadTemperature(requestId = 0))
      device2.expectMsg(Device.ReadTemperature(requestId = 0))

      queryActor.tell(Device.RespondTemperature(requestId = 0, Some(1.0)), device1.ref)
      queryActor.tell(Device.RespondTemperature(requestId = 0, Some(2.0)), device2.ref)
      device2.ref ! PoisonPill

      requester.expectMsg(DeviceGroup.RespondAllTemperatures(
        requestId = 1,
        temperatures = Map(
          "device1" -> DeviceGroup.Temperature(1.0),
          "device2" -> DeviceGroup.Temperature(2.0)
        )
      ))
    }

    "return DeviceTimedOut if device does not answer in time" in {
      val requester = TestProbe()

      val device1 = TestProbe()
      val device2 = TestProbe()

      val queryActor = system.actorOf(DeviceGroupQuery.props(
        actorToDeviceId = Map(device1.ref -> "device1", device2.ref -> "device2"),
        requestId = 1,
        requester = requester.ref,
        timeout = 1.second
      ))

      device1.expectMsg(Device.ReadTemperature(requestId = 0))
      device2.expectMsg(Device.ReadTemperature(requestId = 0))

      queryActor.tell(Device.RespondTemperature(requestId = 0, Some(1.0)), device1.ref)

      requester.expectMsg(DeviceGroup.RespondAllTemperatures(
        requestId = 1,
        temperatures = Map(
          "device1" -> DeviceGroup.Temperature(1.0),
          "device2" -> DeviceGroup.DeviceTimedOut
        )
      ))
    }

  }

}
