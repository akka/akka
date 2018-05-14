/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

/**
 * This example illustrates Actor message flow control with
 * "work pulling pattern" and reliable delivery by tracking
 * sequence numbers and resending missing.
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
   *
   * The `Request` message also contains a `confirmedSeqNr` that is the acknowledgement
   * from the consumer that it has received all messages up to that sequence number.
   *
   * The consumer can send [[Producer.Resend]] if a lost message is detected and then the
   * producer will resend all messages from that sequence number. The producer keeps
   * unconfirmed messages in a buffer to be able to resend them. The buffer size is limited
   * by the request window size.
   */
  object Producer {
    import Consumer.SequencedMessage

    sealed trait ProducerMessage
    final case class Request(upToSeqNr: Long, confirmedSeqNr: Long, consumer: ActorRef[SequencedMessage],
      viaReceiveTimeout: Boolean) extends ProducerMessage
    final case class Resend(fromSeqNr: Long) extends ProducerMessage
    private final case object ProducerTick extends ProducerMessage

    def producer(): Behavior[ProducerMessage] = Behaviors.receiveMessage {
      case Request(upToSeqNr, _, receiver, _) ⇒
        // simulate fast producer
        Behaviors.withTimers { timers ⇒
          timers.startPeriodicTimer(ProducerTick, ProducerTick, 20.millis)
          activeProducer(receiver, currentSeqNr = 1, requestedSeqNr = upToSeqNr, Vector.empty)
        }
    }

    private def activeProducer(
      receiver:       ActorRef[SequencedMessage],
      currentSeqNr:   Long,
      requestedSeqNr: Long,
      unconfirmed:    Vector[SequencedMessage]): Behavior[ProducerMessage] = {

      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case Request(seqNr, confirmedSeqNr, `receiver`, viaReceiveTimeout) ⇒
            val newUnconfirmed = unconfirmed.dropWhile(_.seqNr <= confirmedSeqNr)
            if (viaReceiveTimeout && newUnconfirmed.nonEmpty) {
              // the last message was lost and no more message was sent that would trigger Resend
              ctx.log.info("resending after ReceiveTimeout {}", newUnconfirmed.map(_.seqNr).mkString(", "))
              newUnconfirmed.foreach(receiver ! _)
            }
            if (seqNr > requestedSeqNr) activeProducer(receiver, currentSeqNr, seqNr, newUnconfirmed)
            else Behaviors.same

          case Resend(fromSeqNr) ⇒
            val newUnconfirmed = unconfirmed.dropWhile(_.seqNr < fromSeqNr)
            ctx.log.info("resending {}", newUnconfirmed.map(_.seqNr).mkString(", "))
            newUnconfirmed.foreach(receiver ! _)
            activeProducer(receiver, currentSeqNr, requestedSeqNr, newUnconfirmed)

          case ProducerTick ⇒
            if (currentSeqNr <= requestedSeqNr) {
              ctx.log.info("sent {}", currentSeqNr)
              val seqMsg = SequencedMessage(currentSeqNr, s"msg-$currentSeqNr")
              val newUnconfirmed = unconfirmed :+ seqMsg
              receiver ! seqMsg
              activeProducer(receiver, currentSeqNr + 1, requestedSeqNr, newUnconfirmed)
            } else
              Behaviors.same
        }
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
   *
   * If the consumer receives a message with unexpected sequence number (not previous + 1)
   * it sends [[Producer.Resend]] to the producer and will ignore all messages until
   * the expected sequence number arrives.
   */
  object Consumer {
    import Producer.ProducerMessage
    import Producer.Request
    import Producer.Resend
    sealed trait ConsumerMessage
    final case class SequencedMessage(seqNr: Long, msg: String) extends ConsumerMessage
    private final case object RetryRequest extends ConsumerMessage

    private val RequestWindow = 50

    def consumer(producer: ActorRef[ProducerMessage]): Behavior[SequencedMessage] = {
      Behaviors.setup[ConsumerMessage] { ctx ⇒
        // simulate lost messages
        val flakySelf = ctx.spawn(flakyNetwork(ctx.self, dropProbability = 0.1), "flaky")
        producer ! Request(RequestWindow, 0, flakySelf, viaReceiveTimeout = false)
        ctx.setReceiveTimeout(1.second, RetryRequest)
        consumer(flakySelf, producer, receivedSeqNr = 0, requestedSeqNr = RequestWindow)
      }.narrow
    }

    private def consumer(flakySelf: ActorRef[SequencedMessage], producer: ActorRef[ProducerMessage], receivedSeqNr: Long, requestedSeqNr: Long): Behavior[ConsumerMessage] = {
      def become(receivedSeqNr: Long = receivedSeqNr, requestedSeqNr: Long = requestedSeqNr) =
        consumer(flakySelf, producer, receivedSeqNr, requestedSeqNr)

      def becomeResending(): Behavior[ConsumerMessage] = {
        Behaviors.receive { (ctx, msg) ⇒
          msg match {
            case SequencedMessage(seqNr, msg) ⇒
              if (seqNr == receivedSeqNr + 1) {
                ctx.log.info("received missing {}", seqNr)
                processMsg(ctx, seqNr, msg)
              } else {
                ctx.log.info("ignoring {}, waiting for {}", seqNr, receivedSeqNr + 1)
                Behaviors.same // ignore until we receive the expected
              }
            case RetryRequest ⇒
              // in case the Resend message was lost
              ctx.log.info("retry resend {}", receivedSeqNr + 1)
              producer ! Resend(fromSeqNr = receivedSeqNr + 1)
              Behaviors.same
          }
        }
      }

      def processMsg(ctx: ActorContext[ConsumerMessage], seqNr: Long, msg: String): Behavior[ConsumerMessage] = {
        // simulate slow consumer
        Thread.sleep(100)
        ctx.log.info("processed {}", seqNr)
        if (seqNr == 500) {
          ctx.system.terminate()
          Behaviors.same
        } else if ((requestedSeqNr - seqNr) == RequestWindow / 2) {
          val newRequestedSeqNr = requestedSeqNr + RequestWindow / 2
          ctx.log.info("request {}", newRequestedSeqNr)
          producer ! Request(newRequestedSeqNr, confirmedSeqNr = seqNr, flakySelf, viaReceiveTimeout = false)
          become(seqNr, newRequestedSeqNr)
        } else {
          become(seqNr)
        }
      }

      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case SequencedMessage(seqNr, msg) ⇒
            val expectedSeqNr = receivedSeqNr + 1
            if (seqNr == expectedSeqNr) {
              processMsg(ctx, seqNr, msg)
            } else if (seqNr > expectedSeqNr) {
              ctx.log.info("missing {}, received {}", expectedSeqNr, seqNr)
              producer ! Resend(fromSeqNr = expectedSeqNr)
              becomeResending()
            } else { // seqNr < expectedSeqNr
              ctx.log.info("deduplicate {}, expected {}", seqNr, expectedSeqNr)
              Behaviors.same
            }

          case RetryRequest ⇒
            // in case the Request or the SequencedMessage triggering the Request is lost
            val newRequestedSeqNr = receivedSeqNr + RequestWindow
            ctx.log.info("retry request {}", newRequestedSeqNr)
            producer ! Request(newRequestedSeqNr, receivedSeqNr, flakySelf, viaReceiveTimeout = true)
            become(requestedSeqNr = newRequestedSeqNr)
        }
      }
    }
  }

  // TODO could use watch to detect when producer or consumer are terminated

  def main(args: Array[String]): Unit = {
    ActorSystem[Nothing](mainBehavior, "DeliveryDemo")
  }

  def mainBehavior: Behavior[Nothing] = Behaviors.setup[Nothing] { ctx ⇒
    val p = ctx.spawn(Producer.producer(), "producer")
    // simulate lost messages
    val flakyP = ctx.spawn(flakyNetwork(p, dropProbability = 0.3), "flakyProducer")
    ctx.spawn(Consumer.consumer(flakyP), "consumer")
    Behaviors.empty
  }

  def flakyNetwork[T](destination: ActorRef[T], dropProbability: Double): Behavior[T] =
    Behaviors.receive { (ctx, msg) ⇒
      if (ThreadLocalRandom.current().nextDouble() <= dropProbability)
        ctx.log.info("dropped {}", msg)
      else
        destination ! msg
      Behaviors.same
    }

}
