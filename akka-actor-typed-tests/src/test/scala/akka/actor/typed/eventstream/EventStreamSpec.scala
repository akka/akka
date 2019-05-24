/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import org.scalatest.WordSpecLike

class EventStreamSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import EventStreamSpec._

  private final val ShortWait = 100.millis

  "system event stream".can {
    val eventObjListener: TestProbe[EventObj.type] = testKit.createTestProbe()
    val eventClassListener: TestProbe[EventClass] = testKit.createTestProbe()

    "register subscribers" in {
      testKit.system.eventStream ! Subscribe(eventObjListener.ref)
      testKit.system.eventStream ! Subscribe(eventClassListener.ref)
    }

    "accept published events" in {
      testKit.system.eventStream ! Publish(EventObj)
    }
    "dispatch events to subscribers of that type" in {
      eventObjListener.expectMessage(EventObj)
      eventClassListener.expectNoMessage(ShortWait)
      testKit.system.eventStream ! Publish(EventClass())
      eventClassListener.expectMessage(EventClass())
      eventObjListener.expectNoMessage(ShortWait)
    }

    "unsubscribe subscribers" in {
      testKit.system.eventStream ! Unsubscribe(eventObjListener.ref)
      testKit.system.eventStream ! Publish(EventObj)
      eventObjListener.expectNoMessage(ShortWait)
    }
  }

  "a system event stream subscriber" must {
    val rootEventListener = testKit.createTestProbe[Root]
    val level1EventListener = testKit.createTestProbe[Level1]
    val rootEventListenerForLevel1 = testKit.createTestProbe[Root]
    testKit.system.eventStream ! Subscribe(rootEventListener.ref)
    testKit.system.eventStream ! Subscribe(level1EventListener.ref)
    testKit.system.eventStream ! Subscribe[Level1](rootEventListenerForLevel1.ref)
    "listen for all subclasses of the events" in {
      testKit.system.eventStream ! Publish(Depth1())
      rootEventListener.expectMessage(Depth1())
      level1EventListener.expectNoMessage(ShortWait)
      rootEventListenerForLevel1.expectNoMessage(ShortWait)

      testKit.system.eventStream ! Publish(Depth2())

      rootEventListener.expectMessage(Depth2())
      level1EventListener.expectMessage(Depth2())
      rootEventListenerForLevel1.expectMessage(Depth2())
    }

  }

}

object EventStreamSpec {
  case object EventObj
  case class EventClass()

  sealed trait Root
  case class Depth1() extends Root
  sealed trait Level1 extends Root
  case class Depth2() extends Level1
}
