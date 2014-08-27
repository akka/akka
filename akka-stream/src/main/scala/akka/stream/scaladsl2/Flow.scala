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

sealed trait Flow

object FlowFrom {
  /**
   * Helper to create `Flow` without [[Input]].
   * Example usage: `FlowFrom[Int]`
   */
  def apply[T]: ProcessorFlow[T, T] = ProcessorFlow[T, T](Nil)

  /**
   * Helper to create `Flow` with Input from `Iterable`.
   * Example usage: `FlowFrom(Seq(1,2,3))`
   */
  def apply[T](i: immutable.Iterable[T]): PublisherFlow[T, T] = FlowFrom[T].withInput(IterableIn(i))

  /**
   * Helper to create `Flow` with [[Input]] from `Publisher`.
   */
  def apply[T](p: Publisher[T]): PublisherFlow[T, T] = FlowFrom[T].withInput(PublisherIn(p))
}

trait Input[-In]

/**
 * Default input.
 * Allows to materialize a Flow with this input to Subscriber.
 */
final case class SubscriberIn[-In]() extends Input[In] {
  def subscriber[I <: In]: Subscriber[I] = ???
}

/**
 * Input from Publisher.
 */
final case class PublisherIn[-In](p: Publisher[_ >: In]) extends Input[In]

/**
 * Input from Iterable
 *
 * Changing In from Contravariant to Covariant is needed because Iterable[+A].
 * But this brakes IterableIn variance and we get IterableIn(Seq(1,2,3)): IterableIn[Any]
 */
final case class IterableIn[-In](i: immutable.Iterable[_ >: In]) extends Input[In]

/**
 * Input from Future
 *
 * Changing In from Contravariant to Covariant is needed because Future[+A].
 * But this brakes FutureIn variance and we get FutureIn(Future{1}): FutureIn[Any]
 */
final case class FutureIn[-In](f: Future[_ >: In]) extends Input[In]

trait Output[+Out]

/**
 * Default output.
 * Allows to materialize a Flow with this output to Publisher.
 */
final case class PublisherOut[+Out]() extends Output[Out] {
  def publisher[O >: Out]: Publisher[O] = ???
}

final case class BlackholeOut[+Out]() extends Output[Out] {
  def publisher[O >: Out]: Publisher[O] = ???
}

/**
 * Output to a Subscriber.
 */
final case class SubscriberOut[+Out](s: Subscriber[_ <: Out]) extends Output[Out]

/**
 * Fold output. Reduces output stream according to the given fold function.
 */
final case class FoldOut[T, +Out](zero: T)(f: (T, Out) ⇒ T) extends Output[Out] {
  def future: Future[T] = ???
}

/**
 * Operations with a Flow which has open (no attached) Input.
 *
 * No Out type parameter would be useful for Graph signatures, but we need it here
 * for `withInput` and `prependTransform` methods.
 */
sealed trait HasOpenInput[-In, +Out] extends Flow {
  type Repr[-In, +Out] <: HasOpenInput[In, Out]
  type AfterCloseInput[-In, +Out] <: Flow

  def withInput[I <: In](in: Input[I]): AfterCloseInput[I, Out]

  def prepend[T](f: ProcessorFlow[T, In]): Repr[T, Out]
  def prepend[T](f: PublisherFlow[T, In]): Repr[T, Out]#AfterCloseInput[T, Out]

}

/**
 * Operations with a Flow which has open (no attached) Output.
 *
 * No In type parameter would be useful for Graph signatures, but we need it here
 * for `withOutput`.
 */
trait HasOpenOutput[-In, +Out] extends Flow {
  type Repr[-In, +Out] <: HasOpenOutput[In, Out]
  type AfterCloseOutput[-In, +Out] <: Flow

  // Storing ops in reverse order
  protected def andThen[U](op: AstNode): Repr[In, U]

  def withOutput[O >: Out](out: Output[O]): AfterCloseOutput[In, O]

  def map[T](f: Out ⇒ T): Repr[In, T] =
    transform("map", () ⇒ new Transformer[Out, T] {
      override def onNext(in: Out) = List(f(in))
    })

  def transform[T](name: String, mkTransformer: () ⇒ Transformer[Out, T]): Repr[In, T] = {
    andThen(Transform(name, mkTransformer.asInstanceOf[() ⇒ Transformer[Any, Any]]))
  }

  def append[T](f: ProcessorFlow[Out, T]): Repr[In, T]
  def append[T](f: SubscriberFlow[Out, T]): Repr[In, T]#AfterCloseOutput[In, T]
}

/**
 * Flow without attached input and without attached output, can be used as a `Processor`.
 */
final case class ProcessorFlow[-In, +Out](ops: List[AstNode]) extends HasOpenOutput[In, Out] with HasOpenInput[In, Out] {
  override type Repr[-In, +Out] = ProcessorFlow[In, Out]
  type AfterCloseOutput[-In, +Out] = SubscriberFlow[In, Out]
  type AfterCloseInput[-In, +Out] = PublisherFlow[In, Out]

  override protected def andThen[U](op: AstNode): Repr[In, U] = this.copy(ops = op :: ops)

  def withOutput[O >: Out](out: Output[O]): AfterCloseOutput[In, O] = SubscriberFlow(out, ops)
  def withInput[I <: In](in: Input[I]): AfterCloseInput[I, Out] = PublisherFlow(in, ops)

  override def prepend[T](f: ProcessorFlow[T, In]): Repr[T, Out] =
    ProcessorFlow(ops ::: f.ops)
  override def prepend[T](f: PublisherFlow[T, In]): Repr[T, Out]#AfterCloseInput[T, Out] =
    PublisherFlow(f.input, ops ::: f.ops)

  override def append[T](f: ProcessorFlow[Out, T]): Repr[In, T] = ProcessorFlow(f.ops ++: ops)
  override def append[T](f: SubscriberFlow[Out, T]): Repr[In, T]#AfterCloseOutput[In, T] =
    SubscriberFlow(f.output, f.ops ++: ops)
}

/**
 *  Flow with attached output, can be used as a `Subscriber`.
 */
final case class SubscriberFlow[-In, +Out](output: Output[Out], ops: List[AstNode]) extends HasOpenInput[In, Out] {
  type Repr[-In, +Out] = SubscriberFlow[In, Out]
  type AfterCloseInput[-In, +Out] = RunnableFlow[In, Out]

  def withInput[I <: In](in: Input[I]): AfterCloseInput[I, Out] = RunnableFlow(in, output, ops)
  def withoutOutput: ProcessorFlow[In, Out] = ProcessorFlow(ops)

  override def prepend[T](f: ProcessorFlow[T, In]): Repr[T, Out] =
    SubscriberFlow(output, ops ::: f.ops)
  override def prepend[T](f: PublisherFlow[T, In]): Repr[T, Out]#AfterCloseInput[T, Out] =
    RunnableFlow(f.input, output, ops ::: f.ops)
}

/**
 * Flow with attached input, can be used as a `Publisher`.
 */
final case class PublisherFlow[-In, +Out](input: Input[In], ops: List[AstNode]) extends HasOpenOutput[In, Out] {
  override type Repr[-In, +Out] = PublisherFlow[In, Out]
  type AfterCloseOutput[-In, +Out] = RunnableFlow[In, Out]

  override protected def andThen[U](op: AstNode): Repr[In, U] = this.copy(ops = op :: ops)

  def withOutput[O >: Out](out: Output[O]): AfterCloseOutput[In, O] = RunnableFlow(input, out, ops)
  def withoutInput: ProcessorFlow[In, Out] = ProcessorFlow(ops)

  override def append[T](f: ProcessorFlow[Out, T]): Repr[In, T] = PublisherFlow(input, f.ops ++: ops)
  override def append[T](f: SubscriberFlow[Out, T]): Repr[In, T]#AfterCloseOutput[In, T] =
    RunnableFlow(input, f.output, f.ops ++: ops)

}

/**
 * Flow with attached input and output, can be executed.
 */
final case class RunnableFlow[-In, +Out](input: Input[In], output: Output[Out], ops: List[AstNode]) extends Flow {
  def withoutOutput: PublisherFlow[In, Out] = PublisherFlow(input, ops)
  def withoutInput: SubscriberFlow[In, Out] = SubscriberFlow(output, ops)

  // FIXME
  def run()(implicit materializer: FlowMaterializer): Unit =
    produceTo(new BlackholeSubscriber[Any](materializer.settings.maximumInputBufferSize))

  // FIXME replace with run and input/output factories
  def toPublisher[U >: Out]()(implicit materializer: FlowMaterializer): Publisher[U] =
    input match {
      case PublisherIn(p)   ⇒ materializer.toPublisher(ExistingPublisher(p), ops)
      case IterableIn(iter) ⇒ materializer.toPublisher(IterablePublisherNode(iter), ops)
      case _                ⇒ ???
    }

  def produceTo(subscriber: Subscriber[_ >: Out])(implicit materializer: FlowMaterializer): Unit =
    toPublisher().subscribe(subscriber.asInstanceOf[Subscriber[Out]])

}

