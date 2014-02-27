package akka.streams.impl

import rx.async.api.Processor
import rx.async.spi.{ Publisher, Subscription, Subscriber }
import akka.actor.{ PoisonPill, ActorRef, Props, Actor }
import akka.streams.{ AbstractProducer, Operation, ActorBasedImplementationSettings }
import akka.streams.Operation._
import rx.async.api.Producer
import scala.concurrent.{ Await, ExecutionContext }
import akka.util.Timeout
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec

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

private class SourceProducer[O](source: Source[O], val settings: ActorBasedImplementationSettings) extends ProducerImplementationBits[O] { outer ⇒
  protected def createActor(): ActorRef = settings.refFactory.actorOf(Props(new SourceProducerActor))
  protected def createImplementation(ctx: ContextEffects)(downstream: Downstream[O]): SyncSource = OperationImpl(downstream, ctx, source)

  class SourceProducerActor extends ProducerActor {
    protected def settings: ActorBasedImplementationSettings = outer.settings
  }
}

private class OperationProcessor[I, O](val operation: Operation[I, O], val settings: ActorBasedImplementationSettings)
  extends Processor[I, O]
  with ProducerImplementationBits[O] { outer ⇒

  // TODO: refactor into ConsumerImplementationBits to implement SinkConsumer
  val getSubscriber: Subscriber[I] =
    new Subscriber[I] {
      def onSubscribe(subscription: Subscription): Unit = actor ! OnSubscribed(subscription)
      def onNext(element: I): Unit = actor ! OnNext(element)
      def onComplete(): Unit = actor ! OnComplete
      def onError(cause: Throwable): Unit = actor ! OnError(cause)
    }

  protected def createActor(): ActorRef = settings.refFactory.actorOf(Props(new OperationProcessorActor))
  protected def createImplementation(ctx: ContextEffects)(downstream: Downstream[O]): SyncSource = ???

  case class OnSubscribed(subscription: Subscription)
  case class OnNext(element: I)
  case object OnComplete
  case class OnError(cause: Throwable)

  class OperationProcessorActor extends ProducerActor {
    protected def settings: ActorBasedImplementationSettings = outer.settings

    object InnerSource extends SyncSource {
      var downstream: Downstream[O] = _
      def receiveDownstream(downstream: Downstream[O]): SyncSource = {
        this.downstream = downstream
        this
      }

      def handleRequestMore(n: Int): Effect = RequestMoreFromImplementation(n)
      def handleCancel(): Effect = impl.handleCancel() // TODO: handle case where impl was not yet created
    }
    getPublisher = ActorContextEffects.internalProducer(InnerSource.receiveDownstream, ShutdownActor).getPublisher
    val impl = OperationImpl(UpstreamSideEffects, InnerSource.downstream, ActorContextEffects, operation)
    Effect.run(impl.start())
    var upstream: Subscription = _
    lazy val UpstreamSideEffects = BasicEffects.forSubscription(upstream)
    var needToRequest = 0

    // a special implementation that delays requesting from upstream until upstream
    // has connected
    case class RequestMoreFromImplementation(elements: Int) extends SingleStep {
      def runOne(): Effect =
        if (upstream eq null) {
          needToRequest += elements
          Continue
        } else impl.handleRequestMore(elements)
    }

    override def receive = WaitingForUpstream

    def WaitingForUpstream: Receive = {
      case Initialize ⇒ sender ! "initialized"
      case OnSubscribed(subscription) ⇒
        assert(subscription != null)
        upstream = subscription
        context.become(Running)
        if (needToRequest > 0) {
          Effect.run(impl.handleRequestMore(needToRequest))
          needToRequest = 0
        }
      case RunEffects(e) ⇒ Effect.run(e)
    }
    def Running: Receive = {
      case OnNext(element) ⇒ Effect.run(impl.handleNext(element))
      case OnComplete      ⇒ Effect.run(impl.handleComplete())
      case OnError(cause)  ⇒ Effect.run(impl.handleError(cause))
      case RunEffects(e)   ⇒ Effect.run(e)
    }
  }
}

class PipelineActor(pipeline: Pipeline[_], val settings: ActorBasedImplementationSettings) extends Actor with ProcessorActorImpl {
  Effect.run(OperationImpl(ActorContextEffects, pipeline).start())

  def receive: Receive = {
    case RunEffects(e) ⇒ Effect.run(e)
  }
}

trait ProducerImplementationBits[O] extends Producer[O] {
  protected def createActor(): ActorRef
  protected def settings: ActorBasedImplementationSettings
  protected def createImplementation(ctx: ContextEffects)(downstream: Downstream[O]): SyncSource

  var getPublisher: Publisher[O] = _
  import akka.pattern.ask
  import scala.concurrent.duration._
  implicit val timeout = Timeout(1.second)
  val actor = createActor()
  Await.ready(actor ? Initialize, 1.seconds)
  case object Initialize

  trait ProducerActor extends Actor with ProcessorActorImpl {
    def receive = {
      case Initialize ⇒
        getPublisher = ActorContextEffects.internalProducer(createImplementation(ActorContextEffects), ShutdownActor).getPublisher
        sender ! "initialized"
      case RunEffects(e) ⇒ Effect.run(e)
    }
  }
}

trait ProcessorActorImpl { _: Actor ⇒
  protected def settings: ActorBasedImplementationSettings

  object ActorContextEffects extends AbstractContextEffects {
    def defaultInitialBufferSize: Int = settings.initialFanOutBufferSize
    def defaultMaxBufferSize: Int = settings.maxFanOutBufferSize

    def runStrictInContext(effect: Effect): Unit = if (effect ne Continue) self ! RunEffects(effect)
    def executionContext: ExecutionContext = context.dispatcher
  }
  case object ShutdownActor extends ExternalEffect {
    def run(): Unit = context.stop(self)
  }
}

case class RunEffects(body: Effect)