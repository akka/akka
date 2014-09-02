/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import java.util.concurrent.atomic.AtomicLong
import akka.actor.{ Actor, ActorCell, ActorRef, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, LocalActorRef, Props, RepointableActorRef }
import akka.pattern.ask
import org.reactivestreams.{ Processor, Publisher, Subscriber }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.stream.Transformer
import akka.stream.scaladsl2.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.impl.ActorPublisher
import akka.stream.impl.IterablePublisher
import akka.stream.impl.IteratorPublisher
import akka.stream.impl.TransformProcessorImpl
import akka.stream.impl.ActorProcessor
import akka.stream.impl.ExposedPublisher
import akka.stream.scaladsl2.Source
import akka.stream.scaladsl2.Sink
import akka.stream.scaladsl2.MaterializedFlow
import akka.stream.scaladsl2.IterableSource
import akka.stream.impl.EmptyPublisher
import akka.stream.scaladsl2.IteratorSource
import akka.stream.scaladsl2.PublisherSource
import akka.stream.scaladsl2.ThunkSource
import akka.stream.impl.SimpleCallbackPublisher
import akka.stream.scaladsl2.FutureSource
import akka.stream.impl.FuturePublisher
import akka.stream.impl.ErrorPublisher
import akka.stream.impl.TickPublisher
import akka.stream.scaladsl2.TickSource

/**
 * INTERNAL API
 */
private[akka] object Ast {
  sealed trait AstNode {
    def name: String
  }

  case class Transform(name: String, mkTransformer: () ⇒ Transformer[Any, Any]) extends AstNode

}

/**
 * INTERNAL API
 */
private[akka] case class ActorBasedFlowMaterializer(
  override val settings: MaterializerSettings,
  supervisor: ActorRef,
  flowNameCounter: AtomicLong,
  namePrefix: String)
  extends FlowMaterializer(settings) {
  import akka.stream.impl2.Ast._

  def withNamePrefix(name: String): FlowMaterializer = this.copy(namePrefix = name)

  private def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

  @tailrec private def processorChain(topSubscriber: Subscriber[_], ops: immutable.Seq[AstNode],
                                      flowName: String, n: Int): Subscriber[_] = {
    ops match {
      case op :: tail ⇒
        val opProcessor: Processor[Any, Any] = processorForNode(op, flowName, n)
        opProcessor.subscribe(topSubscriber.asInstanceOf[Subscriber[Any]])
        processorChain(opProcessor, tail, flowName, n - 1)
      case _ ⇒ topSubscriber
    }
  }

  // Ops come in reverse order
  override def materialize[In, Out](source: Source[In], sink: Sink[Out], ops: List[Ast.AstNode]): MaterializedFlow = {
    val flowName = createFlowName()

    // FIXME specialcasing, otherwise some tests fail in FlowIterableSpec due to the injected identityProcessor:
    //       - "have value equality of publisher"
    //       - "produce elements to later subscriber"
    def specialCase: PartialFunction[Source[In], Publisher[Out]] = {
      case PublisherSource(p)      ⇒ p.asInstanceOf[Publisher[Out]]
      case src: IterableSource[In] ⇒ materializeSource(src, flowName).asInstanceOf[Publisher[Out]]
      case src: IteratorSource[In] ⇒ materializeSource(src, flowName).asInstanceOf[Publisher[Out]]
      case src: TickSource[In]     ⇒ materializeSource(src, flowName).asInstanceOf[Publisher[Out]]
    }

    if (ops.isEmpty && specialCase.isDefinedAt(source)) {
      val p = specialCase(source)
      val sinkValue = sink.attach(p, this)
      new MaterializedFlow(source, None, sink, sinkValue)
    } else {
      val (s, p) =
        if (ops.isEmpty) {
          val identityProcessor: Processor[In, Out] = processorForNode(identityTransform, flowName, 1).asInstanceOf[Processor[In, Out]]
          (identityProcessor, identityProcessor)
        } else {
          val opsSize = ops.size
          val outProcessor = processorForNode(ops.head, flowName, opsSize).asInstanceOf[Processor[In, Out]]
          val topSubscriber = processorChain(outProcessor, ops.tail, flowName, opsSize - 1).asInstanceOf[Processor[In, Out]]
          (topSubscriber, outProcessor)
        }
      val sourceValue = source.attach(s, this, flowName)
      val sinkValue = sink.attach(p, this)
      new MaterializedFlow(source, sourceValue, sink, sinkValue)
    }

  }

  private def identityProcessor[I](flowName: String): Processor[I, I] =
    processorForNode(identityTransform, flowName, 1).asInstanceOf[Processor[I, I]]

  private val identityTransform = Transform("identity", () ⇒
    new Transformer[Any, Any] {
      override def onNext(element: Any) = List(element)
    })

  override def materializeSource[In](source: IterableSource[In], flowName: String): Publisher[In] = {
    if (source.iterable.isEmpty) EmptyPublisher[In]
    else ActorPublisher(actorOf(IterablePublisher.props(source.iterable, settings),
      name = s"$flowName-0-iterable"), Some(source.iterable))
  }

  override def materializeSource[In](source: IteratorSource[In], flowName: String): Publisher[In] = {
    if (source.iterator.isEmpty) EmptyPublisher[In]
    else ActorPublisher[In](actorOf(IteratorPublisher.props(source.iterator, settings),
      name = s"$flowName-0-iterator"))
  }

  override def materializeSource[In](source: ThunkSource[In], flowName: String): Publisher[In] = {
    ActorPublisher[In](actorOf(SimpleCallbackPublisher.props(settings, source.f),
      name = s"$flowName-0-thunk"))
  }

  override def materializeSource[In](source: FutureSource[In], flowName: String): Publisher[In] = {
    source.future.value match {
      case Some(Success(element)) ⇒
        ActorPublisher[In](actorOf(IterablePublisher.props(List(element), settings),
          name = s"$flowName-0-future"), Some(source.future))
      case Some(Failure(t)) ⇒
        ErrorPublisher(t).asInstanceOf[Publisher[In]]
      case None ⇒
        ActorPublisher[In](actorOf(FuturePublisher.props(source.future, settings),
          name = s"$flowName-0-future"), Some(source.future))
    }
  }

  override def materializeSource[In](source: TickSource[In], flowName: String): Publisher[In] = {
    ActorPublisher[In](actorOf(TickPublisher.props(source.initialDelay, source.interval, source.tick, settings),
      name = s"$flowName-0-tick"))
  }

  private def processorForNode(op: AstNode, flowName: String, n: Int): Processor[Any, Any] = {
    val impl = actorOf(ActorProcessorFactory.props(settings, op), s"$flowName-$n-${op.name}")
    ActorProcessorFactory(impl)
  }

  private def actorOf(props: Props, name: String): ActorRef = supervisor match {
    case ref: LocalActorRef ⇒
      ref.underlying.attachChild(props, name, systemService = false)
    case ref: RepointableActorRef ⇒
      if (ref.isStarted)
        ref.underlying.asInstanceOf[ActorCell].attachChild(props, name, systemService = false)
      else {
        implicit val timeout = ref.system.settings.CreationTimeout
        val f = (supervisor ? StreamSupervisor.Materialize(props, name)).mapTo[ActorRef]
        Await.result(f, timeout.duration)
      }
    case _ ⇒
      throw new IllegalStateException(s"Stream supervisor must be a local actor, was [${supervisor.getClass.getName}]")
  }

}

/**
 * INTERNAL API
 */
private[akka] object FlowNameCounter extends ExtensionId[FlowNameCounter] with ExtensionIdProvider {
  override def get(system: ActorSystem): FlowNameCounter = super.get(system)
  override def lookup = FlowNameCounter
  override def createExtension(system: ExtendedActorSystem): FlowNameCounter = new FlowNameCounter
}

/**
 * INTERNAL API
 */
private[akka] class FlowNameCounter extends Extension {
  val counter = new AtomicLong(0)
}

/**
 * INTERNAL API
 */
private[akka] object StreamSupervisor {
  def props(settings: MaterializerSettings): Props = Props(new StreamSupervisor(settings))

  case class Materialize(props: Props, name: String)
}

private[akka] class StreamSupervisor(settings: MaterializerSettings) extends Actor {
  import StreamSupervisor._

  def receive = {
    case Materialize(props, name) ⇒
      val impl = context.actorOf(props, name)
      sender() ! impl
  }
}

/**
 * INTERNAL API
 */
private[akka] object ActorProcessorFactory {

  import Ast._
  def props(settings: MaterializerSettings, op: AstNode): Props =
    (op match {
      case t: Transform ⇒ Props(new TransformProcessorImpl(settings, t.mkTransformer()))
    }).withDispatcher(settings.dispatcher)

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}