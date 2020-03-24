/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

object ReliableDeliveryBenchmark {

  final val messagesPerOperation = 100000
  final val timeout = 30.seconds

  object Producer {
    trait Command

    case object Run extends Command
    private case class WrappedRequestNext(r: ProducerController.RequestNext[Consumer.Command]) extends Command

    def apply(
        numberOfMessages: Int,
        producerController: ActorRef[ProducerController.Command[Consumer.Command]]): Behavior[Command] = {
      Behaviors.setup { context =>
        val requestNextAdapter =
          context.messageAdapter[ProducerController.RequestNext[Consumer.Command]](WrappedRequestNext(_))

        Behaviors.receiveMessage {
          case WrappedRequestNext(next) =>
            if (next.confirmedSeqNr >= numberOfMessages) {
              context.log.info("Completed {} messages", numberOfMessages)
              Behaviors.stopped
            } else {
              next.sendNextTo ! Consumer.TheMessage
              Behaviors.same
            }

          case Run =>
            context.log.info("Starting {} messages", numberOfMessages)
            producerController ! ProducerController.Start(requestNextAdapter)
            Behaviors.same
        }
      }
    }
  }

  object Consumer {
    trait Command

    case object TheMessage extends Command

    private case class WrappedDelivery(d: ConsumerController.Delivery[Command]) extends Command

    def apply(consumerController: ActorRef[ConsumerController.Command[Command]]): Behavior[Command] = {
      Behaviors.setup { context =>
        val deliveryAdapter =
          context.messageAdapter[ConsumerController.Delivery[Command]](WrappedDelivery(_))
        consumerController ! ConsumerController.Start(deliveryAdapter)

        Behaviors.receiveMessagePartial {
          case WrappedDelivery(d @ ConsumerController.Delivery(_, confirmTo)) =>
            context.log.trace("Processed {}", d.seqNr)
            confirmTo ! ConsumerController.Confirmed
            Behaviors.same
        }
      }
    }
  }

  object Guardian {

    trait Command
    final case class Run(id: String, numberOfMessages: Int, replyTo: ActorRef[Done]) extends Command
    final case class ProducerTerminated(consumer: ActorRef[Consumer.Command], replyTo: ActorRef[Done]) extends Command

    def apply(): Behavior[Command] = {
      Behaviors.setup { context =>
        Behaviors.receiveMessage {
          case Run(id, numberOfMessages, replyTo) =>
            val consumerController = context.spawn(ConsumerController[Consumer.Command](), s"consumerController-$id")
            val consumer = context.spawn(Consumer(consumerController), s"consumer-$id")

            val producerController = context.spawn(
              ProducerController[Consumer.Command](id, durableQueueBehavior = None),
              s"producerController-$id")
            val producer = context.spawn(Producer(numberOfMessages, producerController), s"producer-$id")
            context.watchWith(producer, ProducerTerminated(consumer, replyTo))

            consumerController ! ConsumerController.RegisterToProducerController(producerController)

            producer ! Producer.Run

            Behaviors.same

          case ProducerTerminated(consumer, replyTo) =>
            context.stop(consumer)
            replyTo ! Done
            Behaviors.same
        }
      }
    }
  }
}
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class ReliableDeliveryBenchmark {
  import ReliableDeliveryBenchmark._

  @Param(Array("10", "50"))
  var window = 0

  implicit var system: ActorSystem[Guardian.Command] = _

  implicit val askTimeout = akka.util.Timeout(timeout)

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem(
      Guardian(),
      "ReliableDeliveryBenchmark",
      ConfigFactory.parseString(s"""
        akka.loglevel = INFO
        akka.reliable-delivery {
          consumer-controller.flow-control-window = $window
        }
      """))
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(messagesPerOperation)
  def echo(): Unit = {
    Await.result(system.ask(Guardian.Run(UUID.randomUUID().toString, messagesPerOperation, _)), timeout)
  }

}
