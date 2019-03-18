/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

object TypedBenchmarkActors {

  // to avoid benchmark to be dominated by allocations of message
  // we pass the respondTo actor ref into the behavior
  final case object Message

  private def echoBehavior(respondTo: ActorRef[Message.type]): Behavior[Message.type] = Behaviors.receive {
    (ctx, msg) =>
      respondTo ! Message
      Behaviors.same
  }

  private def echoSender(
      messagesPerPair: Int,
      onDone: ActorRef[Done],
      batchSize: Int,
      childProps: Props): Behavior[Message.type] =
    Behaviors.setup { ctx =>
      val echo = ctx.spawn(echoBehavior(ctx.self), "echo", childProps)
      var left = messagesPerPair / 2
      var batch = 0

      def sendBatch(): Boolean = {
        if (left > 0) {
          var i = 0
          while (i < batchSize) {
            echo ! Message
            i += 1
          }
          left -= batchSize
          batch = batchSize
          true
        } else
          false
      }

      Behaviors.receiveMessage { msg =>
        batch -= 1
        if (batch <= 0 && !sendBatch()) {
          onDone ! Done
          Behaviors.stopped
        } else {
          Behaviors.same
        }
      }
    }

  case class Start(respondTo: ActorRef[Completed])
  case class Completed(startNanoTime: Long)

  def echoActorsSupervisor(
      numMessagesPerActorPair: Int,
      numActors: Int,
      dispatcher: String,
      batchSize: Int,
      shutdownTimeout: FiniteDuration): Behavior[Start] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Start(respondTo) =>
          // note: no protection against accidentally running bench sessions in parallel
          val sessionBehavior =
            startEchoBenchSession(numMessagesPerActorPair, numActors, dispatcher, batchSize, respondTo)
          ctx.spawnAnonymous(sessionBehavior)
          Behaviors.same
      }
    }

  private def startEchoBenchSession(
      messagesPerPair: Int,
      numActors: Int,
      dispatcher: String,
      batchSize: Int,
      respondTo: ActorRef[Completed]): Behavior[Unit] = {

    val numPairs = numActors / 2

    Behaviors
      .setup[Any] { ctx =>
        val props = Props.empty.withDispatcherFromConfig("akka.actor." + dispatcher)
        val pairs = (1 to numPairs).map { _ =>
          ctx.spawnAnonymous(echoSender(messagesPerPair, ctx.self.narrow[Done], batchSize, props), props)
        }
        val startNanoTime = System.nanoTime()
        pairs.foreach(_ ! Message)
        var interactionsLeft = numPairs
        Behaviors.receiveMessage {
          case Done =>
            interactionsLeft -= 1
            if (interactionsLeft == 0) {
              val totalNumMessages = numPairs * messagesPerPair
              printProgress(totalNumMessages, numActors, startNanoTime)
              respondTo ! Completed(startNanoTime)
              Behaviors.stopped
            } else {
              Behaviors.same
            }

        }
      }
      .narrow[Unit]
  }

  private def printProgress(totalMessages: Long, numActors: Int, startNanoTime: Long) = {
    val durationMicros = (System.nanoTime() - startNanoTime) / 1000
    println(
      f"  $totalMessages messages by $numActors actors took ${durationMicros / 1000} ms, " +
      f"${totalMessages.toDouble / durationMicros}%,.2f M msg/s")
  }
}
