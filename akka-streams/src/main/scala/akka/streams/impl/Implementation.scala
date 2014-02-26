package akka.streams.impl

import rx.async.api.Processor
import rx.async.spi.{ Publisher, Subscription, Subscriber }
import akka.actor.{ PoisonPill, ActorRef, Props, Actor }
import akka.streams.{ AbstractProducer, Operation, ActorBasedImplementationSettings }
import akka.streams.Operation._
import rx.async.api.Producer
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
  @volatile protected var finished = false
  @volatile protected var errorCause: Throwable = null

  val actor = settings.refFactory.actorOf(Props(new ProducerProcessorActor))

  object ProducerShutdown extends IllegalStateException("This producer was already cancelled")

  class ProducerProcessorActor extends ProducerActor {
    val sourceImpl = OperationImpl(DownstreamSideEffects, ActorContextEffects, source)
    Effect.run(sourceImpl.start())

    case class RequestFromSourceImplementation(elements: Int) extends SingleStep {
      def runOne(): Effect =
        if (running) sourceImpl.handleRequestMore(elements)
        else Continue
    }
    case object CancelSourceImplementation extends ExternalEffect {
      def run(): Unit = stopWithError(ProducerShutdown)
    }
    case object ShutdownSourceImplementation extends ExternalEffect {
      def run(): Unit = stopWithError(null)
    }
    case class ShutdownSourceImplementationWithError(cause: Throwable) extends ExternalEffect {
      def run(): Unit = stopWithError(cause)
    }
    def stopWithError(cause: Throwable): Unit = {
      context.become {
        if (cause eq null) Completed
        else Error(cause)
      }
      // the order is important here
      errorCause = cause
      finished = true
      // we need PoisonPill because there may already be `Subscribe` messages lying in the mailbox
      // before we switched running to false
      self ! PoisonPill
    }

    protected lazy val requestFromUpstream = RequestFromSourceImplementation
    protected lazy val cancelUpstream = Effect.step(sourceImpl.handleCancel(), "RunCancelHandler") ~ CancelSourceImplementation
    protected lazy val shutdownComplete = ShutdownSourceImplementation
    protected lazy val shutdownWithError = ShutdownSourceImplementationWithError

    def receive = Running

    def Running: Receive = RunProducer.orElse {
      case RunEffects(e) ⇒ Effect.run(e())
    }
  }
}

private class OperationProcessor[I, O](val operation: Operation[I, O], val settings: ActorBasedImplementationSettings) extends Processor[I, O] with ProducerImplementationBits[O] {
  @volatile protected var finished = false
  protected def errorCause: Throwable = null

  val getSubscriber: Subscriber[I] =
    new Subscriber[I] {
      def onSubscribe(subscription: Subscription): Unit = if (running) actor ! OnSubscribed(subscription)
      def onNext(element: I): Unit = if (running) actor ! OnNext(element)
      def onComplete(): Unit = if (running) actor ! OnComplete
      def onError(cause: Throwable): Unit = if (running) actor ! OnError(cause)
    }

  val actor = settings.refFactory.actorOf(Props(new OperationProcessorActor))

  case class OnSubscribed(subscription: Subscription)
  case class OnNext(element: I)
  case object OnComplete
  case class OnError(cause: Throwable)

  object ProcessorShutdown extends IllegalStateException("This processor was already shutdown")

  class OperationProcessorActor extends ProducerActor {
    val impl = OperationImpl(UpstreamSideEffects, DownstreamSideEffects, ActorContextEffects, operation)
    Effect.run(impl.start())
    var upstream: Subscription = _
    var needToRequest = 0

    case class RequestMoreFromImplementation(elements: Int) extends SingleStep {
      def runOne(): Effect =
        if (upstream eq null) {
          needToRequest += elements
          Continue
        } else impl.handleRequestMore(elements)
    }
    case object CancelImplementation extends SingleStep {
      def runOne(): Effect = {
        context.become(Error(ProcessorShutdown))
        impl.handleCancel()
        // TODO: decommission
      }
    }
    case object ShutdownCompleteImplementation extends SingleStep {
      /* TODO:
      if (upstream eq null) needToRequest += elements // TODO: instantly go into a cancelled mode
      else decommission actor */
      def runOne(): Effect = Continue
    }
    case class ShutdownImplementationWithError(cause: Throwable) extends SingleStep {
      // TODO: see ShutdownCompleteImplementation
      def runOne(): Effect = Continue
    }
    protected lazy val requestFromUpstream = RequestMoreFromImplementation
    protected lazy val cancelUpstream = CancelImplementation
    protected lazy val shutdownComplete = ShutdownCompleteImplementation
    protected lazy val shutdownWithError = ShutdownImplementationWithError

    def receive = WaitingForUpstream

    def WaitingForUpstream: Receive = {
      case OnSubscribed(subscription) ⇒
        upstream = subscription
        context.become(Running)
        if (needToRequest > 0) {
          Effect.run(impl.handleRequestMore(needToRequest))
          needToRequest = 0
        }
      case Subscribe(sub) ⇒ fanOut.subscribe(sub)

      case RunEffects(e)  ⇒ Effect.run(e())
    }
    def Running: Receive = RunProducer orElse {
      case OnNext(element) ⇒ Effect.run(impl.handleNext(element))
      case OnComplete      ⇒ Effect.run(impl.handleComplete())
      case OnError(cause)  ⇒ Effect.run(impl.handleError(cause))
      case RunEffects(e)   ⇒ Effect.run(e())
    }

    lazy val UpstreamSideEffects = BasicEffects.forSubscription(upstream)
  }
}

class PipelineActor(pipeline: Pipeline[_], val settings: ActorBasedImplementationSettings) extends Actor with ProcessorActorImpl {
  Effect.run(OperationImpl(ActorContextEffects, pipeline).start())

  def receive: Receive = {
    case RunEffects(e) ⇒ Effect.run(e())
  }
}

trait ProducerImplementationBits[O] extends Producer[O] with Publisher[O] { impl ⇒
  // invariants regarding finished / errorCause
  // if !finished: actor is still running and will be able to handle Subscribe messages
  // if finished && errorCause eq null: the producer completed normally, actor was stopped
  // if finished && errorCause ne null: the producer completed with an error, actor was stopped
  protected def finished: Boolean
  protected def errorCause: Throwable

  protected def running: Boolean = !finished

  protected def actor: ActorRef
  protected def settings: ActorBasedImplementationSettings

  def getPublisher: Publisher[O] = this
  def subscribe(subscriber: Subscriber[O]): Unit =
    if (running) actor ! Subscribe(subscriber)
    else {
      val cause = errorCause
      if (cause eq null) subscriber.onComplete()
      else subscriber.onError(errorCause)
    }

  case class Subscribe(subscriber: Subscriber[O])

  trait ProducerActor extends Actor with ProcessorActorImpl { outer ⇒
    protected def requestFromUpstream: Int ⇒ Effect
    /** Used from the fanOut if subscriptions have been cancelled before completion but not otherwise */
    protected def cancelUpstream: Effect
    /** Used from the fanOut when the actor can shutdown in the completed state. */
    protected def shutdownComplete: Effect
    /** Used from the fanOut when the actor should shutdown with an error state. */
    protected def shutdownWithError: Throwable ⇒ Effect

    protected def settings: ActorBasedImplementationSettings = impl.settings

    val fanOut = ActorContextEffects.createFanOut[O](requestFromUpstream, cancelUpstream, shutdownComplete, shutdownWithError)
    def DownstreamSideEffects: Downstream[O] = fanOut.downstream

    def RunProducer: Receive = {
      case Subscribe(sub) ⇒ fanOut.subscribe(sub)
    }

    // these states handle the time between `running` = false and the actor receiving the PoisonPill
    def Completed: Receive = {
      case Subscribe(sub) ⇒ sub.onComplete()
      // ignore everything else while completing
    }
    def Error(cause: Throwable): Receive = {
      case Subscribe(sub) ⇒ sub.onError(cause)
      // ignore everything else while completing
    }
  }
}

trait ProcessorActorImpl { _: Actor ⇒
  protected def settings: ActorBasedImplementationSettings

  object ActorContextEffects extends AbstractContextEffects {
    def createFanOut[O](requestMore: Int ⇒ Effect, _cancelUpstream: Effect, _shutdownComplete: Effect, _shutdownWithError: Throwable ⇒ Effect): FanOut[O] =
      new AbstractProducer[O](settings.initialFanOutBufferSize, settings.maxFanOutBufferSize) with FanOut[O] {
        // AbstractProducer are converted into methods returning effects to be run as any others
        protected def requestFromUpstream(elements: Int): Unit = runEffect(requestMore(elements))
        protected def shutdownCancelled(): Unit = runEffect(_cancelUpstream)
        protected def shutdownWithError(cause: Throwable): Unit = runEffect(_shutdownWithError(cause))
        protected def shutdownComplete(): Unit = runEffect(_shutdownComplete)

        var effects: Effect = null
        def runEffect(effect: Effect): Unit = effects ~= effect
        def collectingEffects(body: ⇒ Unit): Effect = {
          assert(effects eq null)
          effects = Continue
          body
          val result = effects
          effects = null
          result
        }

        override protected def moreRequested(subscription: Subscription, elements: Int): Unit =
          runInContext(collectingEffects(super.moreRequested(subscription, elements)))

        override protected def unregisterSubscription(subscription: Subscription): Unit =
          runInContext(collectingEffects(super.unregisterSubscription(subscription)))

        case class Collecting(name: String)(body: ⇒ Unit) extends SingleStep {
          def runOne(): Effect = collectingEffects(body)
        }

        val downstream: Downstream[O] = new Downstream[O] {
          val next = (o: O) ⇒ Collecting("nextFanOut")(pushToDownstream(o))
          val complete: Effect = Collecting("completeFanOut")(completeDownstream())
          val error: Throwable ⇒ Effect = cause ⇒ Collecting("errorFanOut")(abortDownstream(cause))
        }
      }

    def runInContext(body: ⇒ Effect): Unit = runEffectInThisActor(body)
    override implicit def executionContext: ExecutionContext = context.dispatcher
  }

  case class RunEffects(body: () ⇒ Effect) {
    override def toString: String = s"RunEffects(${body.getClass.getSimpleName}})"
  }
  def runEffectInThisActor(body: ⇒ Effect): Unit = self ! RunEffects(body _)
}
