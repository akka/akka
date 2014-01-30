package akka.streams

import rx.async.api.Processor
import akka.actor.{ Props, ActorRefFactory, Actor }
import akka.streams.ops.{ Result, SimpleResult, Implementation }
import rx.async.spi.{ Subscription, Subscriber, Publisher }

object OperationProcessor {
  def apply[I, O](operation: Operation[I, O], settings: ProcessorSettings): Processor[I, O] =
    new OperationProcessor(operation, settings)
}

case class ProcessorSettings(ctx: ActorRefFactory)

private class OperationProcessor[I, O](operation: Operation[I, O], settings: ProcessorSettings) extends Processor[I, O] {
  def isRunning = running

  val getSubscriber: Subscriber[I] =
    new Subscriber[I] {
      def onSubscribe(subscription: Subscription): Unit = if (running) actor ! OnSubscribed(subscription)
      def onNext(element: I): Unit = if (running) actor ! OnNext(element)
      def onComplete(): Unit = if (running) actor ! OnComplete
      def onError(cause: Throwable): Unit = if (running) actor ! OnError(cause)
    }
  val getPublisher: Publisher[O] =
    new Publisher[O] {
      def subscribe(subscriber: Subscriber[O]): Unit = if (running) actor ! Subscribe(subscriber)
    }

  case class OnSubscribed(subscription: Subscription)
  case class OnNext(element: I)
  case object OnComplete
  case class OnError(cause: Throwable)

  case class Subscribe(subscriber: Subscriber[O])
  case class RequestMore(subscriber: Subscriber[O], elements: Int)
  case class CancelSubscription(subscriber: Subscriber[O])

  @volatile private var running = true
  val actor = settings.ctx.actorOf(Props(new OperationProcessorActor))

  class OperationProcessorActor extends Actor {
    val impl = Implementation(operation)
    var upstream: Subscription = _
    var downstream: Subscriber[O] = _

    def receive = {
      case OnSubscribed(subscription) ⇒
        upstream = subscription
        context.become(WaitingForSubscription)
    }
    def WaitingForSubscription: Receive = {
      case Subscribe(sub) ⇒
        sub.onSubscribe(newSubscription(sub))
        downstream = sub
        context.become(Running)
    }
    def Running: Receive = {
      case RequestMore(subscriber, elements) ⇒ run(ops.RequestMore(elements)) // TODO: add FanOutBox into the loop
      case OnNext(element)                   ⇒ run(ops.Emit(element))
      case OnComplete                        ⇒ run(ops.Complete)
      case OnError(cause)                    ⇒ run(ops.Error(cause))

      case CancelSubscription(subscriber)    ⇒ context.become(WaitingForSubscription)
    }

    def run(input: SimpleResult[I]): Unit = handleResult(impl.handle(input))
    def handleResult(result: Result[O]): Unit = result match {
      case ops.Continue ⇒
      case ops.Combine(r1, r2) ⇒
        handleResult(r1); handleResult(r2)
      case ops.RequestMore(n) ⇒ upstream.requestMore(n)
      case ops.Emit(i)        ⇒ downstream.onNext(i)
      case ops.EmitMany(is)   ⇒ is.foreach(downstream.onNext)
      case ops.Complete       ⇒ downstream.onComplete() // and shutdown
      case ops.Error(cause)   ⇒ downstream.onError(cause) // and shutdown
    }

    def newSubscription(subscriber: Subscriber[O]): Subscription =
      new Subscription {
        def requestMore(elements: Int): Unit = if (running) self ! RequestMore(subscriber, elements)
        def cancel(): Unit = if (running) self ! CancelSubscription(subscriber)
      }
  }
}
