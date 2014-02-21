package akka.streams.impl

import rx.async.api.{ Consumer, Producer, Processor }
import rx.async.spi.{ Publisher, Subscription, Subscriber }
import akka.actor.{ ActorRef, Props, Actor }
import akka.streams.{ Producer, AbstractProducer, Operation, ActorBasedImplementationSettings }
import akka.streams.Operation._
import rx.async.api.Producer
import rx.async.spi
import scala.concurrent.ExecutionContext

object Implementation {
  def toProcessor[I, O](operation: Operation[I, O], settings: ActorBasedImplementationSettings): Processor[I, O] =
    new OperationProcessor(operation, settings)

  def toProducer[O](source: Source[O], settings: ActorBasedImplementationSettings): Producer[O] =
    source match {
      // just unpack the internal producer
      case FromProducerSource(i: InternalProducer[O]) ⇒ i
      case _ ⇒ new SourceProducer[O](source, settings)
    }

  def runPipeline(pipeline: Pipeline[_], settings: ActorBasedImplementationSettings): Unit =
    settings.refFactory.actorOf(Props(new PipelineActor(pipeline, settings)))
}

private class SourceProducer[O](source: Source[O], val settings: ActorBasedImplementationSettings) extends ProducerImplementationBits[O] {
  @volatile protected var running = true
  val actor = settings.refFactory.actorOf(Props(new ProducerProcessorActor))

  class ProducerProcessorActor extends ProducerActor {
    val sourceImpl = OperationImpl(DownstreamSideEffects, ActorContextEffects, source)
    Effect.run(sourceImpl.start())
    protected def requestFromUpstream(elements: Int): Unit = Effect.run(sourceImpl.handleRequestMore(elements))
    protected def lastSubscriptionCancelled(): Unit = Effect.run(sourceImpl.handleCancel())

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

    protected def lastSubscriptionCancelled(): Unit =
      /*if (upstream eq null) needToRequest += elements // TODO: instantly go into a cancelled mode
      else*/ Effect.run(impl.handleCancel())

    def receive = WaitingForUpstream

    def WaitingForUpstream: Receive = {
      case OnSubscribed(subscription) ⇒
        upstream = subscription
        context.become(Running)
        if (needToRequest > 0) {
          Effect.run(impl.handleRequestMore(needToRequest))
          needToRequest = 0
        }
      case Subscribe(sub)    ⇒ fanOut.subscribe(sub)

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

class PipelineActor(pipeline: Pipeline[_], val settings: ActorBasedImplementationSettings) extends Actor with ProcessorActorImpl {
  Effect.run(OperationImpl(ActorContextEffects, pipeline).start())

  def receive: Receive = {
    case RunDeferred(body) ⇒ body()
  }
}

trait ProducerImplementationBits[O] extends Producer[O] with Publisher[O] { impl ⇒
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
    protected def lastSubscriptionCancelled(): Unit
    protected def settings: ActorBasedImplementationSettings = impl.settings

    val fanOut = ActorContextEffects.createFanOut[O](requestFromUpstream, lastSubscriptionCancelled)
    def DownstreamSideEffects: Downstream[O] = fanOut.downstream

    def RunProducer: Receive = {
      case Subscribe(sub) ⇒ fanOut.subscribe(sub)
    }
  }
}

trait ProcessorActorImpl { _: Actor ⇒
  protected def settings: ActorBasedImplementationSettings

  object ActorContextEffects extends AbstractContextEffects {
    def createFanOut[O](requestMore: Int ⇒ Unit, _lastSubscriptionCancelled: () ⇒ Unit): FanOut[O] =
      new AbstractProducer[O](settings.initialFanOutBufferSize, settings.maxFanOutBufferSize) with FanOut[O] {
        protected def requestFromUpstream(elements: Int): Unit = requestMore(elements)
        protected def lastSubscriptionCancelled(): Unit = _lastSubscriptionCancelled()

        val innerSubscriber = new Subscriber[O] {
          def onSubscribe(subscription: spi.Subscription): Unit = ???
          def onNext(element: O): Unit = pushToDownstream(element)
          def onComplete(): Unit = completeDownstream()
          def onError(cause: Throwable): Unit = abortDownstream(cause)
        }

        override protected def moreRequested(subscription: Subscription, elements: Int): Unit =
          runInThisActor(super.moreRequested(subscription, elements))

        override protected def unregisterSubscription(subscription: Subscription): Unit =
          runInThisActor(super.unregisterSubscription(subscription))

        val downstream: Downstream[O] = BasicEffects.forSubscriber(innerSubscriber)
      }

    def runInContext(body: ⇒ Effect): Unit = runEffectInThisActor(body)
    override implicit def executionContext: ExecutionContext = context.dispatcher
  }

  case class RunDeferred(body: () ⇒ Unit)
  def runInThisActor(body: ⇒ Unit): Unit = self ! RunDeferred(body _)
  def runEffectInThisActor(body: ⇒ Effect): Unit = runInThisActor(Effect.run(body))
}
