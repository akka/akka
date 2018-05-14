/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

/**
 * This example illustrates Actor message flow control with
 * "work pulling pattern".
 */
object ReliableDeliveryPoc {

  /**
   * The producer will not send more messages than requested by the consumer.
   * It expects an initial [[Producer.Request]] message before sending anything, and that
   * `Request` also contains the destination consumer `ActorRef` that the messages
   * will be sent to.
   *
   * Each message is wrapped in [[Consumer.SequencedMessage]] with a monotonically increasing
   * sequence number without gaps, starting at 1.
   */
  object Producer {
    import Consumer.SequencedMessage

    sealed trait ProducerMessage
    final case class Request(seqNr: Long, consumer: ActorRef[SequencedMessage]) extends ProducerMessage
    private final case object ProducerTick extends ProducerMessage

    def producer(): Behavior[Request] = Behaviors.receiveMessage {
      case Request(seqNr, receiver) ⇒
        // simulate fast producer
        Behaviors.withTimers[ProducerMessage] { timers ⇒
          timers.startPeriodicTimer(ProducerTick, ProducerTick, 20.millis)
          activeProducer(receiver, currentSeqNr = 1, requestedSeqNr = seqNr)
        }.narrow
    }

    private def activeProducer(receiver: ActorRef[SequencedMessage], currentSeqNr: Long, requestedSeqNr: Long): Behavior[ProducerMessage] =
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case Request(seqNr, `receiver`) ⇒
            if (seqNr > requestedSeqNr) activeProducer(receiver, currentSeqNr, seqNr)
            else Behaviors.same

          case ProducerTick ⇒
            if (currentSeqNr == 500)
              ctx.system.terminate()

            if (currentSeqNr <= requestedSeqNr) {
              ctx.log.info("sent {}", currentSeqNr)
              receiver ! SequencedMessage(currentSeqNr, "msg")
              activeProducer(receiver, currentSeqNr + 1, requestedSeqNr)
            } else
              Behaviors.same
        }
      }
  }

  /**
   * The consumer will send [[Producer.Request]] to tell the `producer` that it's ready to
   * receive up to the requested sequence number. It sends new `Request` when
   * half of the requested window is remaining, but it also retries the `Request`
   * if no messages are received because that could be caused by lost messages.
   *
   * The producer will not send more messages than requested.
   */
  object Consumer {
    import Producer.Request
    sealed trait ConsumerMessage
    final case class SequencedMessage(seqNr: Long, msg: String) extends ConsumerMessage
    private final case object RetryRequest extends ConsumerMessage

    private val RequestWindow = 50

    def consumer(producer: ActorRef[Request]): Behavior[SequencedMessage] = {
      Behaviors.setup[ConsumerMessage] { ctx ⇒
        producer ! Request(RequestWindow, ctx.self)
        ctx.setReceiveTimeout(1.second, RetryRequest)
        consumer(producer, receivedSeqNr = 0, requestedSeqNr = RequestWindow)
      }.narrow
    }

    private def consumer(producer: ActorRef[Request], receivedSeqNr: Long, requestedSeqNr: Long): Behavior[ConsumerMessage] = {
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case SequencedMessage(seqNr, msg) ⇒
            ctx.log.info("received {}", seqNr)
            // simulate slow consumer
            Thread.sleep(100)
            if ((requestedSeqNr - seqNr) == RequestWindow / 2) {
              val newRequestedSeqNr = requestedSeqNr + RequestWindow / 2
              ctx.log.info("request seqNr: {}", newRequestedSeqNr)
              producer ! Request(newRequestedSeqNr, ctx.self)
              consumer(producer, seqNr, newRequestedSeqNr)
            } else {
              consumer(producer, seqNr, requestedSeqNr)
            }

          case RetryRequest ⇒
            // in case the Request or the SequencedMessage triggering the Request is lost
            val newRequestedSeqNr = receivedSeqNr + RequestWindow
            ctx.log.info("resend request seqNr: {}", newRequestedSeqNr)
            producer ! Request(newRequestedSeqNr, ctx.self)
            consumer(producer, receivedSeqNr, newRequestedSeqNr)
        }
      }
    }
  }

  // TODO could be expanded with detection of lost messages (gaps in sequence numbers)
  // TODO could use watch to detect when producer or consumer are terminated

  def main(args: Array[String]): Unit = {
    ActorSystem[Nothing](mainBehavior, "DeliveryDemo")
  }

  def mainBehavior: Behavior[Nothing] = Behaviors.setup[Nothing] { ctx ⇒
    val p = ctx.spawn(Producer.producer(), "producer")
    ctx.spawn(Consumer.consumer(p), "consumer")
    Behaviors.empty
  }

}
