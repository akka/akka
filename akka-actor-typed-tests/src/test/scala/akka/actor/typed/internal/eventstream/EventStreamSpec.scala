/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.eventstream

import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.eventstream.EventStream.Unsubscribe
import org.scalatest.WordSpecLike

class EventStreamSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import EventStreamSpec._

  "system event stream".can {
    testKit.system.receptionist
    testKit.system.receptionist
    val eventObjListener: TestProbe[EventObj.type] = testKit.createTestProbe()
    val eventClassListener: TestProbe[EventClass] = testKit.createTestProbe()

    "register subscribers" in {
      testKit.system.eventStream ! EventStream.Subscribe(eventObjListener.ref)
      testKit.system.eventStream ! EventStream.Subscribe(eventClassListener.ref)
    }

    "accept published events" in {
      testKit.system.eventStream ! EventStream.Publish(EventObj)
    }
    "dispatch events to subscribers of that type" in {
      eventObjListener.expectMessage(EventObj)
      eventClassListener.expectNoMessage()
      testKit.system.eventStream ! EventStream.Publish(EventClass())
      eventClassListener.expectMessage(EventClass())
      eventObjListener.expectNoMessage()
    }

    "unsubscribe subscribers" in {
      testKit.system.eventStream ! Unsubscribe(eventObjListener.ref)
      testKit.system.eventStream ! EventStream.Publish(EventObj)
      eventObjListener.expectNoMessage()
    }

  }

}

object EventStreamSpec {
  case object EventObj
  case class EventClass()
}
