/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_2

import akka.testkit.{ AkkaSpec, TestProbe }

import scala.concurrent.duration._

class DeviceSpec extends AkkaSpec {

  "Device actor" must {

    //#device-read-test
    "reply with empty reading if no temperature is known" in {
      val probe = TestProbe()
      val deviceActor = system.actorOf(Device.props("group", "device"))

      deviceActor.tell(Device.ReadTemperature(requestId = 42), probe.ref)
      val response = probe.expectMsgType[Device.RespondTemperature]
      response.requestId should ===(42)
      response.value should ===(None)
    }
    //#device-read-test

    //#device-write-read-test
    "reply with latest temperature reading" in {
      val probe = TestProbe()
      val deviceActor = system.actorOf(Device.props("group", "device"))

      deviceActor.tell(Device.RecordTemperature(requestId = 1, 24.0), probe.ref)
      probe.expectMsg(Device.TemperatureRecorded(requestId = 1))

      deviceActor.tell(Device.ReadTemperature(requestId = 2), probe.ref)
      val response1 = probe.expectMsgType[Device.RespondTemperature]
      response1.requestId should ===(2)
      response1.value should ===(Some(24.0))

      deviceActor.tell(Device.RecordTemperature(requestId = 3, 55.0), probe.ref)
      probe.expectMsg(Device.TemperatureRecorded(requestId = 3))

      deviceActor.tell(Device.ReadTemperature(requestId = 4), probe.ref)
      val response2 = probe.expectMsgType[Device.RespondTemperature]
      response2.requestId should ===(4)
      response2.value should ===(Some(55.0))
    }
    //#device-write-read-test

  }

}
