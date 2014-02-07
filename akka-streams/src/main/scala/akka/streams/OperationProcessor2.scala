package akka.streams

import scala.language.existentials

import rx.async.api.{ Producer, Processor }
import akka.actor.{ Props, ActorRefFactory, Actor }
import rx.async.spi.{ Subscription, Subscriber, Publisher }
import akka.streams.Operation.{ Source, FromProducerSource }
import akka.streams.ops2._
import scala.annotation.tailrec

object OperationProcessor2 {
  def apply[I, O](operation: Operation[I, O], settings: ProcessorSettings): Processor[I, O] =
    new OperationProcessor2(operation, settings)
}

private class OperationProcessor2[I, O](operation: Operation[I, O], settings: ProcessorSettings) extends Processor[I, O] {
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
    val impl = AndThenImpl.implementation(UpstreamSideEffects, DownstreamSideEffects, ActorSubscribable, operation)
    var upstream: Subscription = _

    val fanOutBox: FanOutBox = settings.constructFanOutBox()
    def requestNextBatch(): Unit = if (upstream ne null) run(impl.handleRequestMore(1))
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
      case OnNext(element)                   ⇒ run(impl.handleNext(element)) //run(ops.Emit(element))
      case OnComplete                        ⇒ run(impl.handleComplete()) //run(ops.Complete)
      case OnError(cause)                    ⇒ run(impl.handleError(cause)) //run(ops.Error(cause))

      case RequestMore(subscriber, elements) ⇒ handleRequestMore(subscriber, elements)
      case CancelSubscription(subscriber)    ⇒ handleSubscriptionCancelled(subscriber)

      case RunDeferred(body)                 ⇒ body()
    }

    def run(res: Result[_]): Unit =
      res match {
        // shortcut for simple results
        case s: SideEffect[_]        ⇒ s.runSideEffect()
        case Continue                ⇒
        case r: Step[_]              ⇒ iterate(Vector(r.run()))
        case CombinedResult(results) ⇒ iterate(results)
      }

    @tailrec private[this] def iterate(elements: Vector[Result[_]]): Unit =
      if (elements.isEmpty) ()
      else elements.head match {
        case s: SideEffect[_] ⇒
          s.runSideEffect(); iterate(elements.tail)
        case Continue                ⇒ iterate(elements.tail)
        case r: Step[_]              ⇒ iterate(r.run() +: elements.tail)
        case CombinedResult(results) ⇒ iterate(results ++ elements.tail)
      }

    case class RequestMoreFromUpstream(n: Int) extends SideEffectImpl[Nothing](upstream.requestMore(n))
    case object CancelUpstream extends SideEffectImpl[Nothing](upstream.cancel())
    object UpstreamSideEffects extends Upstream {
      val requestMore: Int ⇒ Result[Nothing] = RequestMoreFromUpstream
      val cancel: Result[Nothing] = CancelUpstream
    }
    case class DeliverNextToDownstream(next: O) extends SideEffectImpl[O](handleOnNext(next))
    case object CompleteDownstream extends SideEffectImpl[Nothing](handleOnComplete())
    case class ErrorDownstream(cause: Throwable) extends SideEffectImpl[Nothing](handleOnError(cause))
    object DownstreamSideEffects extends Downstream[O] {
      val next: O ⇒ Result[O] = DeliverNextToDownstream
      val complete: Result[Nothing] = CompleteDownstream
      val error: (Throwable) ⇒ Result[O] = ErrorDownstream
    }
    object ActorSubscribable extends Subscribable {
      def subscribeTo[O](source: Source[O])(onSubscribe: Upstream ⇒ (SyncSink[O, O], Result[O])): Result[O] =
        Result.step(handleSubscribeTo(source, onSubscribe))
    }

    def handleSubscribeTo[O](source: Source[O], onSubscribeCallback: Upstream ⇒ (SyncSink[O, O], Result[O])): Result[O] = {
      case class RequestMoreFromSub(subscription: Subscription, n: Int) extends SideEffectImpl[Nothing](subscription.requestMore(n))
      case class CancelSub(subscription: Subscription) extends SideEffectImpl[Nothing](subscription.cancel())

      case class SubUpstream(subscription: Subscription) extends Upstream {
        val requestMore: (Int) ⇒ Result[Nothing] = RequestMoreFromSub(subscription, _)
        val cancel: Result[Nothing] = CancelSub(subscription)
      }

      object SubSubscriber extends Subscriber[O] {
        var subscription: Subscription = _
        var sink: SyncSink[O, O] = _
        def onSubscribe(subscription: Subscription): Unit = runInThisActor {
          this.subscription = subscription
          val (handler, result) = onSubscribeCallback(SubUpstream(subscription))
          sink = handler
          run(result)
        }
        def onNext(element: O): Unit = runAndHandleResult(sink.handleNext(element))
        def onComplete(): Unit = runAndHandleResult(sink.handleComplete())
        def onError(cause: Throwable): Unit = runAndHandleResult(sink.handleError(cause))
      }
      val FromProducerSource(prod: Producer[O]) = source
      prod.getPublisher.subscribe(SubSubscriber)
      Continue // we need to wait for onSubscribe being called
    }

    case class RunDeferred(body: () ⇒ Unit)
    def runInThisActor(body: ⇒ Unit): Unit = self ! RunDeferred(body _)
    def runAndHandleResult(body: ⇒ Result[_]): Unit = runInThisActor(run(body))

    def newSubscription(subscriber: Subscriber[O]): Subscription =
      new Subscription {
        def requestMore(elements: Int): Unit = if (running) self ! RequestMore(subscriber, elements)
        def cancel(): Unit = if (running) self ! CancelSubscription(subscriber)
      }
  }
}
