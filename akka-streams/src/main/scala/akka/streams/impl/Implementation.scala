package akka.streams.impl

import asyncrx.api.Processor
import asyncrx.spi.{ Publisher, Subscription, Subscriber }
import akka.actor.{ PoisonPill, ActorRef, Props, Actor }
import akka.streams.{ Operation, ActorBasedStreamGeneratorSettings }
import akka.streams.Operation._
import asyncrx.api.Producer
import scala.concurrent.{ Promise, Await, ExecutionContext }
import akka.util.Timeout
import scala.util.{ Try, Success }

object Implementation {
  def toProcessor[I, O](operation: Operation[I, O], settings: ActorBasedStreamGeneratorSettings): Processor[I, O] =
    new OperationProcessor(operation, settings)

  def toProducer[O](source: Source[O], settings: ActorBasedStreamGeneratorSettings): Producer[O] =
    source match {
      // just unpack the internal producer
      case FromProducerSource(i: InternalProducer[O]) ⇒ i
      case _ ⇒ new SourceProducer[O](source, settings)
    }

  def runPipeline(pipeline: Pipeline[_], settings: ActorBasedStreamGeneratorSettings): Unit =
    settings.refFactory.actorOf(Props(new PipelineActor(pipeline, settings)))
}

private class SourceProducer[O](source: Source[O], val settings: ActorBasedStreamGeneratorSettings) extends ProducerImplementationBits[O] { outer ⇒
  protected def createActor(promise: Promise[Publisher[O]]): ActorRef = settings.refFactory.actorOf(Props(new SourceProducerActor(promise)))

  class SourceProducerActor(promise: Promise[Publisher[O]]) extends ProcessorActor {
    protected def settings: ActorBasedStreamGeneratorSettings = outer.settings
    promise.complete(Try {
      ActorContextEffects.internalProducer(OperationImpl(_: Downstream[O], ActorContextEffects, source), ShutdownActor).getPublisher
    })

    def receive = {
      case RunEffects(e) ⇒ settings.effectExecutor.run(e)
    }
  }
}

private class OperationProcessor[I, O](val operation: Operation[I, O], val settings: ActorBasedStreamGeneratorSettings)
  extends Processor[I, O]
  with ProducerImplementationBits[O] { outer ⇒
  @volatile var finished = false

  // TODO: refactor into ConsumerImplementationBits to implement SinkConsumer
  val getSubscriber: Subscriber[I] =
    new Subscriber[I] {
      def onSubscribe(subscription: Subscription): Unit =
        if (finished) throw new IllegalStateException("Cannot subscribe shutdown subscriber")
        else actor ! OnSubscribed(subscription)
      def onNext(element: I): Unit = sendIfNotFinished(OnNext(element))
      def onComplete(): Unit = sendIfNotFinished(OnComplete)
      def onError(cause: Throwable): Unit = sendIfNotFinished(OnError(cause))

      def sendIfNotFinished(msg: AnyRef): Unit =
        if (!finished) actor ! msg
      // else ignore
    }

  protected def createActor(promise: Promise[Publisher[O]]): ActorRef =
    settings.refFactory.actorOf(Props(new OperationProcessorActor(promise)))

  case class OnSubscribed(subscription: Subscription)
  case class OnNext(element: I)
  case object OnComplete
  case class OnError(cause: Throwable)

  class OperationProcessorActor(promise: Promise[Publisher[O]]) extends ProcessorActor {
    protected def settings: ActorBasedStreamGeneratorSettings = outer.settings

    case object OperationSource extends SyncSource {
      var downstream: Downstream[O] = _
      def receiveDownstream(downstream: Downstream[O]): SyncSource = {
        this.downstream = downstream
        this
      }

      def handleRequestMore(elements: Int): Effect =
        if (upstream eq null) {
          needToRequest += elements
          Continue
        } else impl.handleRequestMore(elements)

      def handleCancel(): Effect =
        if (upstream eq null) {
          cancelling = true
          context.become(Cancelled)
          Continue
        } else impl.handleCancel()
    }
    promise.complete(Success(ActorContextEffects.internalProducer(OperationSource.receiveDownstream, ShutdownActor).getPublisher))
    val impl = OperationImpl(UpstreamSideEffects, OperationSource.downstream, ActorContextEffects, operation)
    settings.effectExecutor.run(impl.start())
    var upstream: Subscription = _
    lazy val UpstreamSideEffects = BasicEffects.forSubscription(upstream)
    var needToRequest = 0

    override def receive = WaitingForUpstream
    var cancelling = false
    override def shutdown(): Unit =
      // if cancelling shutdown needs to be deferred until after a possible subscription
      if (!cancelling) stop()

    def WaitingForUpstream: Receive = {
      case OnSubscribed(subscription) ⇒
        assert(subscription != null)
        upstream = subscription
        context.become(Running)
        if (needToRequest > 0) {
          settings.effectExecutor.run(impl.handleRequestMore(needToRequest))
          needToRequest = 0
        }
      case RunEffects(e) ⇒ settings.effectExecutor.run(e)
    }
    def Cancelled: Receive = {
      case OnSubscribed(subscription) ⇒
        subscription.cancel()
        stop()
    }
    def Running: Receive = {
      case OnNext(element) ⇒ settings.effectExecutor.run(impl.handleNext(element))
      case OnComplete      ⇒ settings.effectExecutor.run(impl.handleComplete())
      case OnError(cause)  ⇒ settings.effectExecutor.run(impl.handleError(cause))
      case RunEffects(e)   ⇒ settings.effectExecutor.run(e)
    }
    def Finishing: Receive = {
      case OnSubscribed(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
      case _                          ⇒ // ignore everything else
    }

    def stop(): Unit = {
      finished = true
      context.become(Finishing)
      self ! PoisonPill
    }
  }
}

class PipelineActor(pipeline: Pipeline[_], val settings: ActorBasedStreamGeneratorSettings) extends ProcessorActor {
  settings.effectExecutor.run(OperationImpl(ActorContextEffects, pipeline).start())

  def receive: Receive = {
    case RunEffects(e) ⇒ settings.effectExecutor.run(e)
  }
}

trait ProducerImplementationBits[O] extends Producer[O] {
  protected def createActor(promise: Promise[Publisher[O]]): ActorRef
  protected def settings: ActorBasedStreamGeneratorSettings

  var getPublisher: Publisher[O] = _
  import scala.concurrent.duration._
  implicit val timeout = Timeout(1.second)
  val actor = {
    val publisherPromise = Promise[Publisher[O]]()
    val res = createActor(publisherPromise)

    getPublisher = Await.result(publisherPromise.future, 1.seconds)
    res
  }
}

abstract class ProcessorActor extends Actor {
  protected def settings: ActorBasedStreamGeneratorSettings
  def shutdown(): Unit = context.stop(self)

  object ActorContextEffects extends AbstractContextEffects {
    def defaultInitialBufferSize: Int = settings.initialFanOutBufferSize
    def defaultMaxBufferSize: Int = settings.maxFanOutBufferSize

    def runStrictInContext(effect: Effect): Unit = if (effect ne Continue) self ! RunEffects(effect)
    def executionContext: ExecutionContext = context.dispatcher
    def runEffectHere(effect: Effect): Unit = settings.effectExecutor.run(effect)
  }
  case object ShutdownActor extends ExternalEffect {
    def run(): Unit = shutdown()
  }
}

case class RunEffects(body: Effect)