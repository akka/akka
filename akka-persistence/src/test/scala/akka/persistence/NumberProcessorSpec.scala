/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence

import scala.language.postfixOps

import com.typesafe.config._

import scala.concurrent.duration._

import akka.actor._
import akka.persistence._

import akka.testkit._

object NumberProcessorSpec {
  case class SetNumber(number: Int)
  case class Add(number: Int)
  case class Subtract(number: Int)
  case object DecrementAndGet
  case object GetNumber

  class NumberProcessorWithPersistentChannel(name: String) extends NamedProcessor(name) {
    var num = 0

    val channel = context.actorOf(PersistentChannel.props(channelId = "stable_id",
      PersistentChannelSettings(redeliverInterval = 30 seconds, redeliverMax = 15)),
      name = "myPersistentChannel")

    def receive = {
      case Persistent(SetNumber(number), _) ⇒ num = number
      case Persistent(Add(number), _)       ⇒ num = num + number
      case Persistent(Subtract(number), _)  ⇒ num = num - number
      case GetNumber                        ⇒ channel ! Deliver(Persistent(num), sender.path)
      case p @ Persistent(DecrementAndGet, _) ⇒
        num = num - 1
        channel ! Deliver(p.withPayload(num), sender.path)
    }
  }
}

/*
 * This test found the problem described in ticket #3933
 */
class NumberProcessorSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "NumberProcessorSpec"))
  with PersistenceSpec {
  import NumberProcessorSpec._

  "A processor using a persistent channel" must {

    "resurrect with the correct state, not replaying confirmed messages to clients" in {
      val deliveredProbe = TestProbe()
      system.eventStream.subscribe(deliveredProbe.testActor, classOf[DeliveredByPersistentChannel])

      val probe = TestProbe()

      val processor = namedProcessor[NumberProcessorWithPersistentChannel]
      processor.tell(GetNumber, probe.testActor)

      val zero = probe.expectMsgType[ConfirmablePersistent]
      zero.confirm()
      zero.payload should equal(0)

      deliveredProbe.expectMsgType[DeliveredByPersistentChannel]

      processor.tell(Persistent(DecrementAndGet), probe.testActor)

      val decrementFrom0 = probe.expectMsgType[ConfirmablePersistent]
      decrementFrom0.confirm()
      decrementFrom0.payload should equal(-1)

      deliveredProbe.expectMsgType[DeliveredByPersistentChannel]

      watch(processor)
      system.stop(processor)
      expectMsgType[Terminated]

      val processorResurrected = namedProcessor[NumberProcessorWithPersistentChannel]
      processorResurrected.tell(Persistent(DecrementAndGet), probe.testActor)

      val decrementFromMinus1 = probe.expectMsgType[ConfirmablePersistent]
      decrementFromMinus1.confirm()
      decrementFromMinus1.payload should equal(-2)

      deliveredProbe.expectMsgType[DeliveredByPersistentChannel]
    }
  }
}

