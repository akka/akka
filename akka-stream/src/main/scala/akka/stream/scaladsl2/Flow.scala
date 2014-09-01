/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.language.higherKinds
import scala.collection.immutable
import scala.concurrent.Future
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import akka.stream.Transformer
import akka.stream.impl.BlackholeSubscriber
import akka.stream.impl2.Ast._
import scala.annotation.unchecked.uncheckedVariance
import akka.stream.impl.BlackholeSubscriber
import scala.concurrent.Promise

sealed trait Flow

object FlowFrom {
  /**
   * Helper to create `Flow` without [[Source]].
   * Example usage: `FlowFrom[Int]`
   */
  def apply[T]: ProcessorFlow[T, T] = ProcessorFlow[T, T](Nil)

  /**
   * Helper to create `Flow` with [[Source]] from `Iterable`.
   * Example usage: `FlowFrom(Seq(1,2,3))`
   */
  def apply[T](i: immutable.Iterable[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(IterableSource(i))

  /**
   * Helper to create `Flow` with [[Source]] from `Publisher`.
   */
  def apply[T](p: Publisher[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(PublisherSource(p))
}

trait Source[-In] {
  def materialize(materializer: FlowMaterializer): (Publisher[In], AnyRef) @uncheckedVariance
}

/**
 * Default input.
 * Allows to materialize a Flow with this input to Subscriber.
 */
final case class SubscriberSource[In]() extends Source[In] {
  override def materialize(materializer: FlowMaterializer): (Publisher[In], AnyRef) = {
    val (s, p) = materializer.ductBuild[In, In](Nil) // FIXME this must be improved somehow
    (p, s)
  }

  def subscriber[I <: In](m: MaterializedSource): Subscriber[I] =
    m.getSourceFor(this).asInstanceOf[Subscriber[I]]
}

/**
 * [[Source]] from `Publisher`.
 */
final case class PublisherSource[In](p: Publisher[_ >: In]) extends Source[In] {
  override def materialize(materializer: FlowMaterializer): (Publisher[In], AnyRef) =
    (p.asInstanceOf[Publisher[In]], p)
}

/**
 * [[Source]] from `Iterable`
 *
 * Changing In from Contravariant to Covariant is needed because Iterable[+A].
 * But this brakes IterableSource variance and we get IterableSource(Seq(1,2,3)): IterableSource[Any]
 */
final case class IterableSource[In](i: immutable.Iterable[_ >: In]) extends Source[In] {
  override def materialize(materializer: FlowMaterializer): (Publisher[In], AnyRef) =
    (materializer.toPublisher(IterablePublisherNode(i), Nil), i) // FIXME this must be improved somehow
}

/**
 * [[Source]] from `Future`
 *
 * Changing In from Contravariant to Covariant is needed because Future[+A].
 * But this brakes FutureSource variance and we get FutureSource(Future{1}): FutureSource[Any]
 */
final case class FutureSource[In](f: Future[_ >: In]) extends Source[In] {
  override def materialize(materializer: FlowMaterializer): (Publisher[In], AnyRef) = ???
}

trait Sink[+Out] {
  def materialize(p: Publisher[Out] @uncheckedVariance, materializer: FlowMaterializer): AnyRef
}

/**
 * Default output.
 * Allows to materialize a Flow with this output to Publisher.
 */
final case class PublisherSink[+Out]() extends Sink[Out] {
  def materialize(p: Publisher[Out] @uncheckedVariance, materializer: FlowMaterializer): AnyRef = p
  def publisher[O >: Out](m: MaterializedSink): Publisher[O] = m.getSinkFor(this).asInstanceOf[Publisher[O]]
}

final case class BlackholeSink[+Out]() extends Sink[Out] {
  override def materialize(p: Publisher[Out] @uncheckedVariance, materializer: FlowMaterializer): AnyRef = {
    val s = new BlackholeSubscriber[Out](materializer.settings.maximumInputBufferSize)
    p.subscribe(s)
    s
  }
}

/**
 * [[Sink]] to a Subscriber.
 */
final case class SubscriberSink[+Out](s: Subscriber[_ <: Out]) extends Sink[Out] {
  override def materialize(p: Publisher[Out] @uncheckedVariance, materializer: FlowMaterializer): AnyRef = {
    p.subscribe(s.asInstanceOf[Subscriber[Out]])
    s
  }
}

/**
 * INTERNAL API
 */
private[akka] object ForeachSink {
  private val ListOfUnit = List(())
}

/**
 * Foreach output. Invokes the given function for each element. Completes the [[#future]] when
 * all elements processed, or stream failed.
 */
final case class ForeachSink[Out](f: Out ⇒ Unit) extends Sink[Out] { // FIXME variance?
  override def materialize(p: Publisher[Out] @uncheckedVariance, materializer: FlowMaterializer): AnyRef = {
    val promise = Promise[Unit]()
    FlowFrom(p).transform("foreach", () ⇒ new Transformer[Out, Unit] {
      override def onNext(in: Out) = { f(in); Nil }
      override def onTermination(e: Option[Throwable]) = {
        e match {
          case None    ⇒ promise.success(())
          case Some(e) ⇒ promise.failure(e)
        }
        Nil
      }
    }).consume()(materializer)
    promise.future
  }
  def future(m: MaterializedSink): Future[Unit] = m.getSinkFor(this).asInstanceOf[Future[Unit]]
}

/**
 * Fold output. Reduces output stream according to the given fold function.
 */
final case class FoldSink[T, +Out](zero: T)(f: (T, Out) ⇒ T) extends Sink[Out] {
  override def materialize(p: Publisher[Out] @uncheckedVariance, materializer: FlowMaterializer): AnyRef = ???
  def future: Future[T] = ???
}

/**
 * Operations with a Flow which has no attached [[Source]].
 *
 * No Out type parameter would be useful for Graph signatures, but we need it here
 * for `withSource` and `prependTransform` methods.
 */
sealed trait HasNoSource[-In, +Out] extends Flow {
  type Repr[-In, +Out] <: HasNoSource[In, Out]
  type AfterAttachingSource[-In, +Out] <: Flow

  def withSource[I <: In](in: Source[I]): AfterAttachingSource[I, Out]

  def prepend[T](f: ProcessorFlow[T, In]): Repr[T, Out]
  def prepend[T](f: FlowWithSource[T, In]): Repr[T, Out]#AfterAttachingSource[T, Out]

}

/**
 * Operations with a Flow which has no attached [[Sink]].
 *
 * No In type parameter would be useful for Graph signatures, but we need it here
 * for `withSink`.
 */
trait HasNoSink[-In, +Out] extends Flow {
  type Repr[-In, +Out] <: HasNoSink[In, Out]
  type AfterAttachingSink[-In, +Out] <: Flow

  // Storing ops in reverse order
  protected def andThen[U](op: AstNode): Repr[In, U]

  def withSink[O >: Out](out: Sink[O]): AfterAttachingSink[In, O]

  def map[T](f: Out ⇒ T): Repr[In, T] =
    transform("map", () ⇒ new Transformer[Out, T] {
      override def onNext(in: Out) = List(f(in))
    })

  def transform[T](name: String, mkTransformer: () ⇒ Transformer[Out, T]): Repr[In, T] = {
    andThen(Transform(name, mkTransformer.asInstanceOf[() ⇒ Transformer[Any, Any]]))
  }

  def append[T](f: ProcessorFlow[Out, T]): Repr[In, T]
  def append[T](f: FlowWithSink[Out, T]): Repr[In, T]#AfterAttachingSink[In, T]

}

/**
 * Flow without attached input and without attached output, can be used as a `Processor`.
 */
final case class ProcessorFlow[-In, +Out](ops: List[AstNode]) extends HasNoSink[In, Out] with HasNoSource[In, Out] {
  override type Repr[-In, +Out] = ProcessorFlow[In, Out]
  type AfterAttachingSink[-In, +Out] = FlowWithSink[In, Out]
  type AfterAttachingSource[-In, +Out] = FlowWithSource[In, Out]

  override protected def andThen[U](op: AstNode): Repr[In, U] = this.copy(ops = op :: ops)

  override def withSink[O >: Out](out: Sink[O]): AfterAttachingSink[In, O] = FlowWithSink(out, ops)
  override def withSource[I <: In](in: Source[I]): AfterAttachingSource[I, Out] = FlowWithSource(in, ops)

  override def prepend[T](f: ProcessorFlow[T, In]): Repr[T, Out] =
    ProcessorFlow(ops ::: f.ops)
  override def prepend[T](f: FlowWithSource[T, In]): Repr[T, Out]#AfterAttachingSource[T, Out] =
    FlowWithSource(f.input, ops ::: f.ops)

  override def append[T](f: ProcessorFlow[Out, T]): Repr[In, T] = ProcessorFlow(f.ops ++: ops)
  override def append[T](f: FlowWithSink[Out, T]): Repr[In, T]#AfterAttachingSink[In, T] =
    FlowWithSink(f.output, f.ops ++: ops)
}

/**
 *  Flow with attached output, can be used as a `Subscriber`.
 */
final case class FlowWithSink[-In, +Out](output: Sink[Out], ops: List[AstNode]) extends HasNoSource[In, Out] {
  type Repr[-In, +Out] = FlowWithSink[In, Out]
  type AfterAttachingSource[-In, +Out] = RunnableFlow[In, Out]

  override def withSource[I <: In](in: Source[I]): AfterAttachingSource[I, Out] = RunnableFlow(in, output, ops)
  def withoutSink: ProcessorFlow[In, Out] = ProcessorFlow(ops)

  override def prepend[T](f: ProcessorFlow[T, In]): Repr[T, Out] =
    FlowWithSink(output, ops ::: f.ops)
  override def prepend[T](f: FlowWithSource[T, In]): Repr[T, Out]#AfterAttachingSource[T, Out] =
    RunnableFlow(f.input, output, ops ::: f.ops)

  def toSubscriber[I <: In]()(implicit materializer: FlowMaterializer): Subscriber[I] = {
    val subIn = SubscriberSource[I]()
    val mf = withSource(subIn).run()
    subIn.subscriber(mf)
  }
}

/**
 * Flow with attached input, can be used as a `Publisher`.
 */
final case class FlowWithSource[-In, +Out](input: Source[In], ops: List[AstNode]) extends HasNoSink[In, Out] {
  override type Repr[-In, +Out] = FlowWithSource[In, Out]
  type AfterAttachingSink[-In, +Out] = RunnableFlow[In, Out]

  override protected def andThen[U](op: AstNode): Repr[In, U] = this.copy(ops = op :: ops)

  override def withSink[O >: Out](out: Sink[O]): AfterAttachingSink[In, O] = RunnableFlow(input, out, ops)
  def withoutSource: ProcessorFlow[In, Out] = ProcessorFlow(ops)

  override def append[T](f: ProcessorFlow[Out, T]): Repr[In, T] = FlowWithSource(input, f.ops ++: ops)
  override def append[T](f: FlowWithSink[Out, T]): Repr[In, T]#AfterAttachingSink[In, T] =
    RunnableFlow(input, f.output, f.ops ++: ops)

  def toPublisher[U >: Out]()(implicit materializer: FlowMaterializer): Publisher[U] = {
    val pubOut = PublisherSink[Out]()
    val mf = withSink(pubOut).run()
    pubOut.publisher(mf)
  }

  def publishTo(subscriber: Subscriber[_ >: Out])(implicit materializer: FlowMaterializer): Unit =
    toPublisher().subscribe(subscriber.asInstanceOf[Subscriber[Out]])

  def consume()(implicit materializer: FlowMaterializer): Unit =
    withSink(BlackholeSink()).run()

}

/**
 * Flow with attached input and output, can be executed.
 */
final case class RunnableFlow[-In, +Out](input: Source[In], output: Sink[Out], ops: List[AstNode]) extends Flow {
  def withoutSink: FlowWithSource[In, Out] = FlowWithSource(input, ops)
  def withoutSource: FlowWithSink[In, Out] = FlowWithSink(output, ops)

  def run()(implicit materializer: FlowMaterializer): MaterializedFlow = {
    val (inPublisher, inValue) = input.materialize(materializer)
    val p = materializer.toPublisher(ExistingPublisher(inPublisher), ops).asInstanceOf[Publisher[Out]]
    val outValue = output.materialize(p, materializer)
    new MaterializedFlow(input, inValue, output, outValue)
  }
}

class MaterializedFlow(sourceKey: AnyRef, matSource: AnyRef, sinkKey: AnyRef, matSink: AnyRef) extends MaterializedSource with MaterializedSink {
  override def getSourceFor(key: AnyRef): AnyRef =
    if (key == sourceKey) matSource
    else throw new IllegalArgumentException(s"Source key [$key] doesn't match the source [$sourceKey] of this flow")

  def getSinkFor(key: AnyRef): AnyRef =
    if (key == sinkKey) matSink
    else throw new IllegalArgumentException(s"Sink key [$key] doesn't match the sink [$sinkKey] of this flow")
}

trait MaterializedSource {
  def getSourceFor(sourceKey: AnyRef): AnyRef
}

trait MaterializedSink {
  def getSinkFor(sinkKey: AnyRef): AnyRef
}
