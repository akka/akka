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
import akka.stream.impl.EmptyPublisher
import akka.stream.impl.ActorPublisher
import akka.stream.impl.IterablePublisher
import akka.stream.impl.TransformProcessorImpl
import akka.stream.impl.ActorProcessor
import akka.stream.impl.ExposedPublisher

/**
 * INTERNAL API
 */
private[akka] object Ast {
  sealed trait AstNode {
    def name: String
  }

  case class Transform(name: String, mkTransformer: () ⇒ Transformer[Any, Any]) extends AstNode

  trait PublisherNode[I] {
    private[akka] def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[I]
  }

  final case class ExistingPublisher[I](publisher: Publisher[I]) extends PublisherNode[I] {
    def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String) = publisher
  }

  final case class IterablePublisherNode[I](iterable: immutable.Iterable[I]) extends PublisherNode[I] {
    def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[I] =
      if (iterable.isEmpty) EmptyPublisher.asInstanceOf[Publisher[I]]
      else ActorPublisher[I](materializer.actorOf(IterablePublisher.props(iterable, materializer.settings),
        name = s"$flowName-0-iterable"), Some(iterable))
  }

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
  override def toPublisher[I, O](publisherNode: PublisherNode[I], ops: List[AstNode]): Publisher[O] = {
    val flowName = createFlowName()
    if (ops.isEmpty) publisherNode.createPublisher(this, flowName).asInstanceOf[Publisher[O]]
    else {
      val opsSize = ops.size
      val opProcessor = processorForNode(ops.head, flowName, opsSize)
      val topSubscriber = processorChain(opProcessor, ops.tail, flowName, opsSize - 1)
      publisherNode.createPublisher(this, flowName).subscribe(topSubscriber.asInstanceOf[Subscriber[I]])
      opProcessor.asInstanceOf[Publisher[O]]
    }
  }

  private val identityTransform = Transform("identity", () ⇒
    new Transformer[Any, Any] {
      override def onNext(element: Any) = List(element)
    })

  def processorForNode(op: AstNode, flowName: String, n: Int): Processor[Any, Any] = {
    val impl = actorOf(ActorProcessorFactory.props(settings, op), s"$flowName-$n-${op.name}")
    ActorProcessorFactory(impl)
  }

  def actorOf(props: Props, name: String): ActorRef = supervisor match {
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