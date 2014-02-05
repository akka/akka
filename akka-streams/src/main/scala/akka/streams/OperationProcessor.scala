package akka.streams

import scala.language.existentials

import rx.async.api.{ Producer, Processor }
import akka.actor.{ Props, ActorRefFactory, Actor }
import akka.streams.ops._
import rx.async.spi.{ Subscription, Subscriber, Publisher }
import akka.streams.Operation.FromProducerSource

object OperationProcessor {
  def apply[I, O](operation: Operation[I, O], settings: ProcessorSettings): Processor[I, O] =
    new OperationProcessor(operation, settings)
}

case class ProcessorSettings(ctx: ActorRefFactory, constructFanOutBox: () ⇒ FanOutBox)

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

  class OperationProcessorActor extends Actor with WithFanOutBox {
    val impl = Implementation(operation)
    var upstream: Subscription = _

    val fanOutBox: FanOutBox = settings.constructFanOutBox()
    def requestNextBatch(): Unit = if (upstream ne null) run(ops.RequestMore(1))
    def allSubscriptionsCancelled(): Unit = context.become(WaitingForDownstream) // or autoUnsubscribe
    def fanOutBoxFinished(): Unit = {} // ignore for now

    def receive = WaitingForUpstream

    def WaitingForUpstream: Receive = {
      case OnSubscribed(subscription) ⇒
        upstream = subscription
        if (hasSubscribers) {
          if (fanOutBox.state == FanOutBox.Ready) requestNextBatch()
          context.become(Running)
        } else context.become(WaitingForDownstream)
      case Subscribe(sub) ⇒
        sub.onSubscribe(newSubscription(sub))
        handleNewSubscription(sub)
      case RequestMore(subscriber, elements) ⇒ handleRequestMore(subscriber, elements)
      case CancelSubscription(subscriber)    ⇒ handleSubscriptionCancelled(subscriber)
    }
    def WaitingForDownstream: Receive = {
      case Subscribe(sub) ⇒
        sub.onSubscribe(newSubscription(sub))
        handleNewSubscription(sub)
        context.become(Running)
    }
    def Running: Receive = {
      case Subscribe(sub) ⇒
        sub.onSubscribe(newSubscription(sub))
        handleNewSubscription(sub)
      case OnNext(element)                   ⇒ run(ops.Emit(element))
      case OnComplete                        ⇒ run(ops.Complete)
      case OnError(cause)                    ⇒ run(ops.Error(cause))

      case RequestMore(subscriber, elements) ⇒ handleRequestMore(subscriber, elements)
      case CancelSubscription(subscriber)    ⇒ handleSubscriptionCancelled(subscriber)

      case RunDeferred(body)                 ⇒ body()
    }

    def run(input: SimpleResult[I]): Unit = handleResult(impl.handle(input))
    def handleResult(result: Result[O]): Unit = result match {
      case ops.Continue ⇒
      case ops.Combine(r1, r2) ⇒
        handleResult(r1); handleResult(r2)
      case s: ops.Subscribe[_, O]                            ⇒ handleSubSubscription(s)
      case ops.RequestMore(n)                                ⇒ upstream.requestMore(n)
      case ops.Emit(publisher: InternalPublisherTemplate[O]) ⇒ handleSubPublisher(publisher)
      case ops.Emit(i)                                       ⇒ handleOnNext(i)
      case ops.EmitMany(is)                                  ⇒ is.foreach(handleOnNext)
      case ops.Complete                                      ⇒ handleOnComplete() // and shutdown
      case ops.Error(cause)                                  ⇒ handleOnError(cause) // and shutdown
      case SubRequestMore(sub, elements)                     ⇒ sub.requestMore(elements)
      case SubEmit(sub, element)                             ⇒ sub.onNext(element)
      case SubComplete(sub)                                  ⇒ sub.onComplete()
      case SubError(sub, cause)                              ⇒ sub.onError(cause)
    }

    // internal sub-subscriptions
    def handleSubSubscription[I](subscribe: ops.Subscribe[I, O]): Unit =
      subscribe.producer match {
        case FromProducerSource(producer) ⇒ producer.getPublisher.subscribe(new InternalSubscriber(subscribe.handlerFactory))
      }
    case class SubRequestMore(subscription: Subscription, elements: Int) extends CustomBackchannelResult
    class InternalSubscriber[I2](handlerFactory: SubscriptionResults ⇒ SubscriptionHandler[I2, O]) extends Subscriber[I2] {
      var handler: SubscriptionHandler[I2, O] = _
      def onSubscribe(subscription: Subscription): Unit =
        runAndHandleResult {
          val results = new SubscriptionResults {
            def requestMore(n: Int): Result[Nothing] = SubRequestMore(subscription, n)
          }
          handler = handlerFactory(results)
          // FIXME: is this really meant with handler.initial? To run it as if it were the result of the
          //        complete chain
          handler.initial
        }
      def onNext(element: I2): Unit = runAndHandleResult(handler.handle(ops.Emit(element)))
      def onComplete(): Unit = runAndHandleResult(handler.handle(ops.Complete))
      def onError(cause: Throwable): Unit = runAndHandleResult(handler.handle(ops.Error(cause)))
    }

    // internal sub-producers
    case class SubEmit[T](subscriber: Subscriber[T], element: T) extends CustomForwardResult[Nothing]
    case class SubComplete(subscriber: Subscriber[_]) extends CustomForwardResult[Nothing]
    case class SubError(subscriber: Subscriber[_], cause: Throwable) extends CustomForwardResult[Nothing]
    def handleSubPublisher[O2](publisherTemplate: InternalPublisherTemplate[O2]): Unit = {
      val subResults = new SubscriptionResults {
        def requestMore(n: Int): Result[Nothing] = ops.RequestMore(n)
      }

      object SubProducer extends Producer[O2] with Publisher[O2] {
        def getPublisher: Publisher[O2] = this

        var singleSubscriber: Subscriber[O2] = _

        def subscribe(subscriber: Subscriber[O2]): Unit = {
          require(singleSubscriber == null) // FIXME: later add FanOutBox
          singleSubscriber = subscriber

          val pubResults = new PublisherResults[O2] {
            def emit(o: O2): Result[Producer[O2]] = SubEmit(subscriber, o)
            def complete: Result[Producer[O2]] = SubComplete(subscriber)
            def error(cause: Throwable): Result[Producer[O2]] = SubError(subscriber, cause)
          }
          val handler = publisherTemplate.f(subResults)(pubResults)
          subscriber.onSubscribe(new Subscription {
            def requestMore(elements: Int): Unit = runAndHandleResult(handler.handle(ops.RequestMore(elements)).asInstanceOf[Result[O]])
            def cancel(): Unit = ??? // FIXME: what shall we do with cancelled subscriber?
          })
        }
      }

      handleOnNext(SubProducer.asInstanceOf[O])
    }

    case class RunDeferred(body: () ⇒ Unit)
    def runInThisActor(body: ⇒ Unit): Unit = self ! RunDeferred(body _)
    def runAndHandleResult(body: ⇒ Result[O]): Unit = runInThisActor(handleResult(body))

    def newSubscription(subscriber: Subscriber[O]): Subscription =
      new Subscription {
        def requestMore(elements: Int): Unit = if (running) self ! RequestMore(subscriber, elements)
        def cancel(): Unit = if (running) self ! CancelSubscription(subscriber)
      }
  }
}
