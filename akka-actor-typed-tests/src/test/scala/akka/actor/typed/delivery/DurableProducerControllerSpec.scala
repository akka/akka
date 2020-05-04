/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import DurableProducerQueue.MessageSent
import ProducerController.MessageWithConfirmation
import org.scalatest.wordspec.AnyWordSpecLike

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.delivery.internal.ProducerControllerImpl

class DurableProducerControllerSpec
    extends ScalaTestWithActorTestKit("""
  akka.reliable-delivery.consumer-controller.flow-control-window = 20
  akka.reliable-delivery.consumer-controller.resend-interval-min = 1s
  """)
    with AnyWordSpecLike
    with LogCapturing {
  import DurableProducerQueue.NoQualifier
  import TestConsumer.sequencedMessage
  import TestDurableProducerQueue.TestTimestamp

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  "ProducerController with durable queue" must {

    "load initial state and resend unconfirmed" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val durable = TestDurableProducerQueue[TestConsumer.Job](
        Duration.Zero,
        DurableProducerQueue.State(
          currentSeqNr = 5,
          highestConfirmedSeqNr = 2,
          confirmedSeqNr = Map(NoQualifier -> (2L -> TestTimestamp)),
          unconfirmed = Vector(
            DurableProducerQueue.MessageSent(3, TestConsumer.Job("msg-3"), false, NoQualifier, TestTimestamp),
            DurableProducerQueue.MessageSent(4, TestConsumer.Job("msg-4"), false, NoQualifier, TestTimestamp))))

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, Some(durable)), s"producerController-${idCount}")
          .unsafeUpcast[ProducerControllerImpl.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      // no request to producer since it has unconfirmed to begin with
      producerProbe.expectNoMessage()

      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController).asFirst)
      consumerControllerProbe.expectNoMessage(50.millis)
      producerController ! ProducerControllerImpl.Request(3L, 13L, true, false)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController))

      val sendTo = producerProbe.receiveMessage().sendNextTo
      sendTo ! TestConsumer.Job("msg-5")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 5, producerController))

      testKit.stop(producerController)
    }

    "store confirmations" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val stateHolder =
        new AtomicReference[DurableProducerQueue.State[TestConsumer.Job]](DurableProducerQueue.State.empty)
      val durable = TestDurableProducerQueue[TestConsumer.Job](
        Duration.Zero,
        stateHolder,
        (_: DurableProducerQueue.Command[_]) => false)

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, Some(durable)), s"producerController-${idCount}")
          .unsafeUpcast[ProducerControllerImpl.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController))
      producerProbe.awaitAssert {
        stateHolder.get() should ===(
          DurableProducerQueue.State(
            2,
            0,
            Map.empty,
            Vector(MessageSent(1, TestConsumer.Job("msg-1"), ack = false, NoQualifier, TestTimestamp))))
      }
      producerController ! ProducerControllerImpl.Request(1L, 10L, true, false)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(
          DurableProducerQueue.State(2, 1, Map(NoQualifier -> (1L -> TestTimestamp)), Vector.empty))
      }

      val replyTo = createTestProbe[Long]()
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 2, producerController, ack = true))
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-3"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 3, producerController, ack = true))
      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-4"), replyTo.ref)
      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 4, producerController, ack = true))
      producerController ! ProducerControllerImpl.Ack(3)
      producerProbe.awaitAssert {
        stateHolder.get() should ===(
          DurableProducerQueue.State(
            5,
            3,
            Map(NoQualifier -> (3L -> TestTimestamp)),
            Vector(MessageSent(4, TestConsumer.Job("msg-4"), ack = true, NoQualifier, TestTimestamp))))
      }

      testKit.stop(producerController)
    }

    "reply to MessageWithConfirmation after storage" in {
      nextId()
      val consumerControllerProbe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()

      val durable =
        TestDurableProducerQueue[TestConsumer.Job](Duration.Zero, DurableProducerQueue.State.empty[TestConsumer.Job])

      val producerController =
        spawn(ProducerController[TestConsumer.Job](producerId, Some(durable)), s"producerController-${idCount}")
          .unsafeUpcast[ProducerControllerImpl.InternalCommand]
      val producerProbe = createTestProbe[ProducerController.RequestNext[TestConsumer.Job]]()
      producerController ! ProducerController.Start(producerProbe.ref)

      producerController ! ProducerController.RegisterConsumer(consumerControllerProbe.ref)

      val replyTo = createTestProbe[Long]()

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-1"), replyTo.ref)
      replyTo.expectMessage(1L)

      consumerControllerProbe.expectMessage(sequencedMessage(producerId, 1, producerController, ack = true))
      producerController ! ProducerControllerImpl.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().askNextTo ! MessageWithConfirmation(TestConsumer.Job("msg-2"), replyTo.ref)
      replyTo.expectMessage(2L)

      testKit.stop(producerController)
    }
  }

}
