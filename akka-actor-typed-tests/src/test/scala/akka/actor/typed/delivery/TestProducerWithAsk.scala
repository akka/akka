/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout

object TestProducerWithAsk {

  trait Command
  final case class RequestNext(askTo: ActorRef[ProducerController.MessageWithConfirmation[TestConsumer.Job]])
      extends Command
  private case object Tick extends Command
  private final case class Confirmed(seqNr: Long) extends Command
  private case object AskTimeout extends Command

  private implicit val askTimeout: Timeout = 10.seconds

  def apply(
      delay: FiniteDuration,
      replyProbe: ActorRef[Long],
      producerController: ActorRef[ProducerController.Start[TestConsumer.Job]]): Behavior[Command] = {
    Behaviors.setup { context =>
      context.setLoggerName("TestProducerWithConfirmation")
      val requestNextAdapter: ActorRef[ProducerController.RequestNext[TestConsumer.Job]] =
        context.messageAdapter(req => RequestNext(req.askNextTo))
      producerController ! ProducerController.Start(requestNextAdapter)

      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(Tick, Tick, delay)
        idle(0, replyProbe)
      }
    }
  }

  private def idle(n: Int, replyProbe: ActorRef[Long]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Tick                => Behaviors.same
      case RequestNext(sendTo) => active(n + 1, replyProbe, sendTo)
      case Confirmed(seqNr) =>
        replyProbe ! seqNr
        Behaviors.same
    }
  }

  private def active(
      n: Int,
      replyProbe: ActorRef[Long],
      sendTo: ActorRef[ProducerController.MessageWithConfirmation[TestConsumer.Job]]): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Tick =>
          val msg = s"msg-$n"
          ctx.log.info("sent {}", msg)
          ctx.ask(
            sendTo,
            (askReplyTo: ActorRef[Long]) =>
              ProducerController.MessageWithConfirmation(TestConsumer.Job(msg), askReplyTo)) {
            case Success(seqNr) => Confirmed(seqNr)
            case Failure(_)     => AskTimeout
          }
          idle(n, replyProbe)

        case RequestNext(_) =>
          throw new IllegalStateException("Unexpected RequestNext, already got one.")

        case Confirmed(seqNr) =>
          ctx.log.info("Reply Confirmed [{}]", seqNr)
          replyProbe ! seqNr
          Behaviors.same

        case AskTimeout =>
          ctx.log.warn("Timeout")
          Behaviors.same
      }
    }
  }

}
