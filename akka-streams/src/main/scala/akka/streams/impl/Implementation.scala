package akka.streams
package impl

import rx.async.api.{ Consumer, Producer, Processor }
import rx.async.spi.{ Publisher, Subscription, Subscriber }
import akka.actor.{ ActorRef, Props, Actor }
import Operation._
import akka.streams.ActorBasedImplementationSettings
import Operation.FromConsumerSink
import Operation.Pipeline
import rx.async.spi

object Implementation {
  def forOperation[I, O](operation: Operation[I, O], settings: ActorBasedImplementationSettings): Processor[I, O] =
    new OperationProcessor(operation, settings)

  def forSource[O](source: Source[O], settings: ActorBasedImplementationSettings): Producer[O] =
    new SourceProducer[O](source, settings)

  def forPipeline(pipeline: Pipeline[_], settings: ActorBasedImplementationSettings): Unit =
    settings.refFactory.actorOf(Props(new PipelineActor(pipeline)))
}

private class SourceProducer[O](source: Source[O], val settings: ActorBasedImplementationSettings) extends ProducerImplementationBits[O] {
  @volatile protected var running = true
  val actor = settings.refFactory.actorOf(Props(new ProducerProcessorActor))

  class ProducerProcessorActor extends ProducerActor {
    val sourceImpl = OperationImpl(DownstreamSideEffects, ActorContextEffects, source)
    Effect.run(sourceImpl.start())
    protected def requestFromUpstream(elements: Int): Unit = Effect.run(sourceImpl.handleRequestMore(elements))

    def receive = Running

    def Running: Receive = RunProducer.orElse {
      case RunDeferred(body) ⇒ body()
    }
  }
}

private class OperationProcessor[I, O](val operation: Operation[I, O], val settings: ActorBasedImplementationSettings) extends Processor[I, O] with ProducerImplementationBits[O] {
  def isRunning = running

  val getSubscriber: Subscriber[I] =
    new Subscriber[I] {
      def onSubscribe(subscription: Subscription): Unit = if (running) actor ! OnSubscribed(subscription)
      def onNext(element: I): Unit = if (running) actor ! OnNext(element)
      def onComplete(): Unit = if (running) actor ! OnComplete
      def onError(cause: Throwable): Unit = if (running) actor ! OnError(cause)
    }

  @volatile protected var running = true
  val actor = settings.refFactory.actorOf(Props(new OperationProcessorActor))

  case class OnSubscribed(subscription: Subscription)
  case class OnNext(element: I)
  case object OnComplete
  case class OnError(cause: Throwable)

  class OperationProcessorActor extends ProducerActor {
    val impl = OperationImpl(UpstreamSideEffects, DownstreamSideEffects, ActorContextEffects, operation)
    Effect.run(impl.start())
    var upstream: Subscription = _
    var needToRequest = 0

    protected def requestFromUpstream(elements: Int): Unit =
      if (upstream eq null) needToRequest += elements
      else Effect.run(impl.handleRequestMore(elements))

    def receive = WaitingForUpstream

    def WaitingForUpstream: Receive = {
      case OnSubscribed(subscription) ⇒
        upstream = subscription
        context.become(Running)
        if (needToRequest > 0) {
          Effect.run(impl.handleRequestMore(needToRequest))
          needToRequest = 0
        }
      case Subscribe(sub)    ⇒ fanOutBox.subscribe(sub)

      case RunDeferred(body) ⇒ body()
    }
    def Running: Receive = RunProducer orElse {
      case OnNext(element)   ⇒ Effect.run(impl.handleNext(element))
      case OnComplete        ⇒ Effect.run(impl.handleComplete())
      case OnError(cause)    ⇒ Effect.run(impl.handleError(cause))

      case RunDeferred(body) ⇒ body()
    }

    lazy val UpstreamSideEffects = BasicEffects.forSubscription(upstream)
  }
}

class PipelineActor(pipeline: Pipeline[_]) extends Actor with ProcessorActorImpl {
  Effect.run(OperationImpl(ActorContextEffects, pipeline).start())

  def receive: Receive = {
    case RunDeferred(body) ⇒ body()
  }
}

trait ProducerImplementationBits[O] extends Producer[O] with Publisher[O] {
  protected def running: Boolean
  protected def actor: ActorRef
  protected def settings: ActorBasedImplementationSettings

  def getPublisher: Publisher[O] = this
  def subscribe(subscriber: Subscriber[O]): Unit = if (running) actor ! Subscribe(subscriber)

  case class Subscribe(subscriber: Subscriber[O])
  case class RequestMore(subscriber: Subscriber[O], elements: Int)
  case class CancelSubscription(subscriber: Subscriber[O])

  trait ProducerActor extends Actor with ProcessorActorImpl { outer ⇒
    protected def requestFromUpstream(elements: Int): Unit

    val fanOutBox = new AbstractProducer[O] with Subscriber[O] {
      protected def requestFromUpstream(elements: Int): Unit = outer.requestFromUpstream(elements)
      protected def handleOverflow(value: O): Unit = {} // drop, TODO: maybe log?

      startWith(Producer.State.Active)

      def onSubscribe(subscription: spi.Subscription): Unit = ???
      def onNext(element: O): Unit = pushToDownstream(element)
      def onComplete(): Unit = completeDownstream()
      def onError(cause: Throwable): Unit = completeDownstreamWithError(cause)

      override protected def requestFromUpstreamIfRequired(): Unit =
        runInThisActor(super.requestFromUpstreamIfRequired())
      override protected def unregisterSubscription(subscription: Subscription): Unit =
        runInThisActor(super.unregisterSubscription(subscription))
    }

    def DownstreamSideEffects = BasicEffects.forSubscriber(fanOutBox)

    def RunProducer: Receive = {
      case Subscribe(sub) ⇒ fanOutBox.subscribe(sub)
    }
  }
}

trait ProcessorActorImpl { _: Actor ⇒
  object ActorContextEffects extends ContextEffects {
    def subscribeTo[O](source: Source[O])(onSubscribeCallback: Upstream ⇒ (SyncSink[O], Effect)): Effect =
      source match {
        case FromProducerSource(prod: Producer[O]) ⇒ subscribeToProducer(prod, onSubscribeCallback)
        case InternalSource(handler)               ⇒ ContextEffects.subscribeToInternalSource(handler, onSubscribeCallback)
        // TODO: what to do in the remaining cases? We can always build a full-fledged Producer, but is that what's needed?
      }

    def subscribeToProducer[O](producer: Producer[O], onSubscribeCallback: Upstream ⇒ (SyncSink[O], Effect)): Effect =
      Effect.step({
        object SubSubscriber extends Subscriber[O] {
          var subscription: Subscription = _
          var sink: SyncSink[O] = _
          def onSubscribe(subscription: Subscription): Unit = runEffectInThisActor {
            this.subscription = subscription
            val (handler, effect) = onSubscribeCallback(BasicEffects.forSubscription(subscription))
            sink = handler
            effect
          }
          def onNext(element: O): Unit = runEffectInThisActor(sink.handleNext(element))

          def onComplete(): Unit =
            if (sink eq null) {
              val (handler, effect) = onSubscribeCallback(new Upstream {
                val cancel: Effect = Continue
                val requestMore: (Int) ⇒ Effect = _ ⇒ Continue
              })
              runEffectInThisActor(effect ~ handler.handleComplete())
            } else runEffectInThisActor(sink.handleComplete())
          // FIXME: add same handling for onError as for completed
          def onError(cause: Throwable): Unit = runEffectInThisActor(sink.handleError(cause))
        }
        producer.getPublisher.subscribe(SubSubscriber)
        Continue // we need to wait for onSubscribe being called
      }, s"SubscribeTo($producer)")

    override def subscribeFrom[O](sink: Sink[O])(onSubscribe: Downstream[O] ⇒ (SyncSource, Effect)): Effect =
      Effect.step({
        val FromConsumerSink(consumer: Consumer[O]) = sink
        class SubSubscription(source: SyncSource) extends Subscription {
          def requestMore(elements: Int): Unit = runEffectInThisActor(source.handleRequestMore(elements))
          def cancel(): Unit = runEffectInThisActor(source.handleCancel())
        }
        val (handler, effect) = onSubscribe(BasicEffects.forSubscriber(consumer.getSubscriber))
        consumer.getSubscriber.onSubscribe(new SubSubscription(handler))
        effect
      }, s"SubscribeFrom($sink)")

    def expose[O](source: Source[O]): Producer[O] = {
      source match {
        case InternalSource(handler) ⇒
          object InternalProducer extends Producer[O] with Publisher[O] {
            var singleSubscriber: Subscriber[O] = _
            def subscribe(subscriber: Subscriber[O]): Unit = runEffectInThisActor {
              require(singleSubscriber eq null) // TODO: add FanOutBox
              this.singleSubscriber = subscriber
              val (upstream, effect) = handler(BasicEffects.forSubscriber(subscriber))
              subscriber.onSubscribe(new Subscription {
                def requestMore(elements: Int): Unit = runEffectInThisActor(upstream.handleRequestMore(elements))
                def cancel(): Unit = runEffectInThisActor(upstream.handleCancel())
              })
              effect
            }

            def getPublisher: Publisher[O] = this
          }

          InternalProducer
      }
    }
  }

  case class RunDeferred(body: () ⇒ Unit)
  def runInThisActor(body: ⇒ Unit): Unit = self ! RunDeferred(body _)
  def runEffectInThisActor(body: ⇒ Effect): Unit = runInThisActor(Effect.run(body))
}
