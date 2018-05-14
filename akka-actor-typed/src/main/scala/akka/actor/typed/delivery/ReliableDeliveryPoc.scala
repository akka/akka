/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.StashBuffer

/**
 * This example illustrates Actor message flow control with
 * "work pulling pattern" and reliable delivery by tracking
 * sequence numbers and resending missing.
 */
object ReliableDeliveryPoc {

  /**
   * The `ProducerController` will not send more messages than requested by the `ConsumerController`.
   * It expects an initial [[ProducerController.Request]] message before sending anything, and that
   * `Request` also contains the destination consumer `ActorRef` that the messages
   * will be sent to.
   *
   * When there is demand from the consumer side the `ProducerController` sends `RequestNext` to the
   * actual producer, which is then allowed to send one message to the `ProducerController`. The `RequestMessage`
   * message is defined via a factory function so that the producer can decide what type to use.
   * If there is still demand a new `RequestNext` is sent to the producer immediately. The producer
   * and `ProducerController` are supposed to be local so that these messages are fast and not lost.
   *
   * Each message is wrapped by the `ProducerController` in [[ConsumerController.SequencedMessage]] with
   * a monotonically increasing sequence number without gaps, starting at 1.
   *
   * The `Request` message also contains a `confirmedSeqNr` that is the acknowledgement
   * from the consumer that it has received and processed all messages up to that sequence number.
   *
   * The `ConsumerController` can send [[ProducerController.Resend]] if a lost message is detected and then the
   * producer will resend all messages from that sequence number. The producer keeps
   * unconfirmed messages in a buffer to be able to resend them. The buffer size is limited
   * by the request window size.
   *
   * The resending is optional, and the `ConsumerController` can be started with `resendLost=false`
   * to ignore lost messages, and then the `ProducerController` will not buffer unconfirmed messages.
   * In that mode it provides only flow control but no reliable delivery.
   */
  object ProducerController {

    import ConsumerController.SequencedMessage

    sealed trait ProducerMessage
    final case class Request[T](confirmedSeqNr: Long, upToSeqNr: Long, consumer: ActorRef[SequencedMessage[T]],
      supportResend: Boolean, viaReceiveTimeout: Boolean) extends ProducerMessage {
      require(confirmedSeqNr < upToSeqNr)
    }
    final case class Resend(fromSeqNr: Long) extends ProducerMessage
    private case class Msg[T](msg: T) extends ProducerMessage

    def behavior[T: ClassTag, RequestNext](
      requestNextFactory: ActorRef[T] ⇒ RequestNext,
      producer:           ActorRef[RequestNext]): Behavior[ProducerMessage] = {

      Behaviors.setup { ctx ⇒
        val msgAdapter: ActorRef[T] = ctx.messageAdapter(msg ⇒ Msg(msg))
        val requestNext = requestNextFactory(msgAdapter)

        Behaviors.receiveMessagePartial {
          case Request(confirmedSeqNr, upToSeqNr, receiver: ActorRef[SequencedMessage[T]] @unchecked, supportResend, _) ⇒
            producer ! requestNext
            val unconfirmed: Option[Vector[SequencedMessage[T]]] = if (supportResend) Some(Vector.empty) else None
            active[T, RequestNext](producer, requestNext, requested = true, receiver,
              currentSeqNr = confirmedSeqNr + 1, requestedSeqNr = upToSeqNr, unconfirmed)
        }
      }
    }

    private def active[T: ClassTag, RequestNext](
      producer:       ActorRef[RequestNext],
      requestNext:    RequestNext,
      requested:      Boolean,
      receiver:       ActorRef[SequencedMessage[T]],
      currentSeqNr:   Long,
      requestedSeqNr: Long,
      unconfirmed:    Option[Vector[SequencedMessage[T]]]): Behavior[ProducerMessage] = { // FIXME use OptionVal

      def become(
        requested:      Boolean,
        currentSeqNr:   Long,
        requestedSeqNr: Long,
        unconfirmed:    Option[Vector[SequencedMessage[T]]]): Behavior[ProducerMessage] =
        active(producer, requestNext, requested, receiver, currentSeqNr, requestedSeqNr, unconfirmed)

      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case Msg(m: T) ⇒
            if (requested && currentSeqNr <= requestedSeqNr) {
              ctx.log.info("sent {}", currentSeqNr)
              val seqMsg = SequencedMessage(currentSeqNr, m)
              val newUnconfirmed = unconfirmed match {
                case Some(u) ⇒ Some(u :+ seqMsg)
                case None    ⇒ None // no resending, no need to keep unconfirmed
              }

              receiver ! seqMsg
              val newRequested =
                if (currentSeqNr == requestedSeqNr)
                  false
                else {
                  producer ! requestNext
                  true
                }
              become(newRequested, currentSeqNr + 1, requestedSeqNr, newUnconfirmed)
            } else {
              throw new IllegalStateException(s"Unexpected Msg when no demand, requested $requested, " +
                s"requestedSeqNr $requestedSeqNr, currentSeqNr $currentSeqNr")
            }

          case Request(confirmedSeqNr, seqNr, rcv, supportResend, viaReceiveTimeout) ⇒
            if (rcv != receiver) {
              // FIXME change of receiver should be supported
              ctx.log.warning(s"Unexpected receiver {}, expected {}", rcv, receiver)
            }
            val newUnconfirmed =
              if (supportResend) unconfirmed match {
                case Some(u) ⇒ Some(u.dropWhile(_.seqNr <= confirmedSeqNr))
                case None    ⇒ Some(Vector.empty)
              }
              else None

            if (viaReceiveTimeout && newUnconfirmed.nonEmpty) {
              // the last message was lost and no more message was sent that would trigger Resend
              newUnconfirmed.foreach { u ⇒
                ctx.log.info("resending after ReceiveTimeout {}", u.map(_.seqNr).mkString(", "))
                u.foreach(receiver ! _)
              }
            }
            if (seqNr > requestedSeqNr) {
              if (!requested && (seqNr - currentSeqNr) > 0)
                producer ! requestNext
              become(requested = true, currentSeqNr, seqNr, newUnconfirmed)
            } else Behaviors.same

          case Resend(fromSeqNr) ⇒
            unconfirmed match {
              case Some(u) ⇒
                val newUnconfirmed = u.dropWhile(_.seqNr < fromSeqNr)
                ctx.log.info("resending {}", newUnconfirmed.map(_.seqNr).mkString(", "))
                newUnconfirmed.foreach(receiver ! _)
                become(requested, currentSeqNr, requestedSeqNr, Some(newUnconfirmed))
              case None ⇒
                throw new IllegalStateException("Resend not supported, run the ConsumerController with resendLost = true")
            }

        }
      }
    }
  }

  /**
   * The destination (consumer) will start the flow by sending an initial `Confirmed` message
   * to the `ConsumerController`. It can have sequence number 0 or from where it would like to start.
   * The `ConsumerController` will then send [[ProducerController.Request]] to tell the `ProducerController`
   * that it's ready to receive up to the requested sequence number. It sends new `Request` when
   * half of the requested window is remaining, but it also retries the `Request`
   * if no messages are received because that could be caused by lost messages.
   *
   * The producer will not send more messages than requested.
   *
   * Received messages are wrapped in [[ConsumerController.Delivery]] sent to the destination,
   * which is supposed to reply with [[ConsumerController.Confirmed]] when it has processed the message.
   * Next `Delivery` is not sent until the previous is confirmed, but we could support more than
   * one if that would be preferred.
   *
   * If the `ConsumerController` receives a message with unexpected sequence number (not previous + 1)
   * it sends [[ProducerController.Resend]] to the producer and will ignore all messages until
   * the expected sequence number arrives.
   */
  object ConsumerController {

    import ProducerController.ProducerMessage
    import ProducerController.Request
    import ProducerController.Resend

    sealed trait ConsumerMessage
    final case class SequencedMessage[T](seqNr: Long, msg: T) extends ConsumerMessage
    private final case object RetryRequest extends ConsumerMessage

    // FIXME name?
    final case class Delivery[T](seqNr: Long, msg: T, confirmTo: ActorRef[Confirmed[T]])
    final case class Confirmed[T](seqNr: Long, deliverNextTo: ActorRef[Delivery[T]]) extends ConsumerMessage

    private val RequestWindow = 50

    def behavior[T](producer: ActorRef[ProducerMessage], resendLost: Boolean): Behavior[ConsumerMessage] = {
      Behaviors.receiveMessagePartial {
        case Confirmed(seqNr, deliverTo: ActorRef[Delivery[T]] @unchecked) ⇒
          Behaviors.setup[ConsumerMessage] { ctx ⇒
            // simulate lost messages from producerController to consumerController
            val flakySelf = ctx.spawn(flakyNetwork[SequencedMessage[T]](ctx.self, dropProbability = 0.1), "flaky")
            val requestedSeqNr = seqNr + RequestWindow
            producer ! Request(seqNr, requestedSeqNr, flakySelf, resendLost, viaReceiveTimeout = false)
            ctx.setReceiveTimeout(1.second, RetryRequest)
            val stashBuffer = StashBuffer[ConsumerMessage](100)
            active[T](flakySelf, producer, deliverTo, stashBuffer, resendLost, receivedSeqNr = seqNr, requestedSeqNr)
          }
      }
    }

    private def active[T](
      flakySelf: ActorRef[SequencedMessage[T]], producer: ActorRef[ProducerMessage],
      destination: ActorRef[Delivery[T]], stashBuffer: StashBuffer[ConsumerMessage],
      resendLost: Boolean, receivedSeqNr: Long, requestedSeqNr: Long): Behavior[ConsumerMessage] = {

      def become(destination: ActorRef[Delivery[T]], receivedSeqNr: Long = receivedSeqNr, requestedSeqNr: Long = requestedSeqNr) =
        active[T](flakySelf, producer, destination, stashBuffer, resendLost, receivedSeqNr, requestedSeqNr)

      def becomeResending(): Behavior[ConsumerMessage] = {
        Behaviors.receive { (ctx, msg) ⇒
          msg match {
            case SequencedMessage(seqNr, msg: T @unchecked) ⇒
              if (seqNr == receivedSeqNr + 1) {
                ctx.log.info("received missing {}", seqNr)
                destination ! Delivery(seqNr, msg, ctx.self)
                becomeWaitingForConfirmation(seqNr)
              } else {
                ctx.log.info("ignoring {}, waiting for {}", seqNr, receivedSeqNr + 1)
                Behaviors.same // ignore until we receive the expected
              }

            case RetryRequest ⇒
              // in case the Resend message was lost
              ctx.log.info("retry resend {}", receivedSeqNr + 1)
              producer ! Resend(fromSeqNr = receivedSeqNr + 1)
              Behaviors.same

            case Confirmed(seqNr, _) ⇒
              // TODO if we would have more than one in flight we would have to keep track of these
              ctx.log.warning("Unexpected confirmed {}", seqNr)
              Behaviors.same
          }
        }
      }

      def becomeWaitingForConfirmation(expectedSeqNr: Long): Behavior[ConsumerMessage] = {
        Behaviors.receive { (ctx, msg) ⇒
          msg match {
            case Confirmed(seqNr, deliverTo: ActorRef[Delivery[T]] @unchecked) ⇒
              if (seqNr > expectedSeqNr) {
                throw new IllegalStateException(s"Expected confirmation of seqNr [$expectedSeqNr], but got higher [$seqNr]")
              } else if (seqNr != expectedSeqNr) {
                ctx.log.info(
                  "Expected confirmation of seqNr [{}] but got [{}]. Perhaps the destination was restarted.",
                  expectedSeqNr, seqNr)
              }
              ctx.log.info("Confirmed {}, stashed {}", seqNr, stashBuffer.size)
              val newRequestedSeqNr =
                if ((requestedSeqNr - seqNr) == RequestWindow / 2) {
                  val newRequestedSeqNr = requestedSeqNr + RequestWindow / 2
                  ctx.log.info("request {}", newRequestedSeqNr)
                  producer ! Request(confirmedSeqNr = seqNr, newRequestedSeqNr, flakySelf, resendLost, viaReceiveTimeout = false)
                  newRequestedSeqNr
                } else {
                  requestedSeqNr
                }
              // FIXME can we use unstashOne instead of all?
              stashBuffer.unstashAll(ctx, become(deliverTo, seqNr, newRequestedSeqNr))
            case _ ⇒
              ctx.log.info("Stash [{}]", msg)
              stashBuffer.stash(msg)
              Behaviors.same
          }
        }
      }

      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case SequencedMessage(seqNr, msg: T @unchecked) ⇒
            val expectedSeqNr = receivedSeqNr + 1
            if (seqNr == expectedSeqNr) {
              destination ! Delivery(seqNr, msg, ctx.self)
              becomeWaitingForConfirmation(seqNr)
            } else if (seqNr > expectedSeqNr) {
              ctx.log.info("missing {}, received {}", expectedSeqNr, seqNr)
              if (resendLost) {
                producer ! Resend(fromSeqNr = expectedSeqNr)
                becomeResending()
              } else {
                destination ! Delivery(seqNr, msg, ctx.self)
                becomeWaitingForConfirmation(seqNr)
              }
            } else { // seqNr < expectedSeqNr
              ctx.log.info("deduplicate {}, expected {}", seqNr, expectedSeqNr)
              Behaviors.same
            }

          case RetryRequest ⇒
            // in case the Request or the SequencedMessage triggering the Request is lost
            val newRequestedSeqNr = receivedSeqNr + RequestWindow
            ctx.log.info("retry request {}", newRequestedSeqNr)
            producer ! Request(receivedSeqNr, newRequestedSeqNr, flakySelf, resendLost, viaReceiveTimeout = true)
            become(destination, requestedSeqNr = newRequestedSeqNr)

          case Confirmed(seqNr, _) ⇒
            // TODO if we would have more than one in flight we would have to keep track of these
            ctx.log.warning("Unexpected confirmed {}", seqNr)
            Behaviors.same
        }
      }
    }
  }

  object MyProducer {

    trait MyProducerMessage
    final case class MyRequestNext(producer: ActorRef[String]) extends MyProducerMessage
    private final case object ProducerTick extends MyProducerMessage

    def behavior: Behavior[MyProducerMessage] = {
      // simulate fast producer
      Behaviors.withTimers { timers ⇒
        timers.startPeriodicTimer(ProducerTick, ProducerTick, 20.millis)
        idle
      }
    }

    private val idle: Behavior[MyProducerMessage] = {
      Behaviors.receiveMessage {
        case ProducerTick              ⇒ Behaviors.same
        case MyRequestNext(controller) ⇒ active(controller)
      }
    }

    private def active(controller: ActorRef[String]): Behavior[MyProducerMessage] = {
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case ProducerTick ⇒
            val msg = "msg"
            ctx.log.info("sent {}", msg)
            controller ! msg
            idle

          case MyRequestNext(_) ⇒
            throw new IllegalStateException("Unexpected RequestNext, already got one.")
        }
      }
    }

    // FIXME it must be possible to restart the producer, and then it needs to retrieve the request state,
    // which would complicate the protocol. A more pragmatic, and easier to use, approach might be to
    // allow for buffering of messages from the producer in the ProducerController if it it has no demand.
    // Then the restarted producer can assume that it can send next message. As a recommendation it
    // should not abuse this capability.

  }

  object MyConsumer {

    import ConsumerController.Confirmed
    import ConsumerController.Delivery

    trait MyConsumerMessage
    final case class MyDelivery(d: Delivery[String]) extends MyConsumerMessage
    final case class SomeAsyncJob(d: Delivery[String]) extends MyConsumerMessage

    def behavior(controller: ActorRef[Confirmed[String]]): Behavior[MyConsumerMessage] =
      Behaviors.setup { ctx ⇒
        val deliverTo: ActorRef[Delivery[String]] = ctx.messageAdapter(MyDelivery.apply)
        controller ! Confirmed(seqNr = 0, deliverTo)

        Behaviors.receiveMessage {
          case MyDelivery(d) ⇒
            // confirmation can be later, asynchronously
            // schedule to simulate slow consumer
            ctx.schedule(10.millis, ctx.self, SomeAsyncJob(d))
            if (d.seqNr >= 500) {
              ctx.system.terminate()
            }
            Behaviors.same

          case SomeAsyncJob(d) ⇒
            ctx.log.info("processed {}", d.seqNr)
            d.confirmTo ! Confirmed(d.seqNr, deliverTo)
            Behaviors.same
        }
      }

    // FIXME it must be possible to restart the consumer, then it might send a non-matching Confirmed(seqNr)
  }

  // TODO could use watch to detect when producer or consumer are terminated

  def main(args: Array[String]): Unit = {
    ActorSystem[Nothing](mainBehavior, "DeliveryDemo")
  }

  def mainBehavior: Behavior[Nothing] = Behaviors.setup[Nothing] { ctx ⇒
    val myProducer = ctx.spawn(MyProducer.behavior, name = "myProducer")
    val producerController = ctx.spawn(
      ProducerController.behavior(MyProducer.MyRequestNext.apply, myProducer),
      "producerController")
    // simulate lost messages from consumerController to producerController
    val flaky = ctx.spawn(flakyNetwork(producerController, dropProbability = 0.3), "flakyProducer")
    val consumerController = ctx.spawn(ConsumerController.behavior(flaky, resendLost = true), "consumerController")
    ctx.spawn(MyConsumer.behavior(consumerController), name = "destination")
    Behaviors.empty
  }

  def flakyNetwork[T](destination: ActorRef[T], dropProbability: Double): Behavior[T] = {
    Behaviors.receive { (ctx, msg) ⇒
      if (ThreadLocalRandom.current().nextDouble() < dropProbability)
        ctx.log.info("dropped {}", msg)
      else
        destination ! msg
      Behaviors.same
    }
  }

}
