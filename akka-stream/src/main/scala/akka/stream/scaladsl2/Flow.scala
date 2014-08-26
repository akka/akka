/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.impl.Ast
import org.reactivestreams.{ Subscriber, Publisher }

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import akka.stream.{ Transformer, OverflowStrategy, FlattenStrategy }

sealed trait Flow[-In, +Out] {
  val transform: Transform[In, Out]
}

object From {
  /**
   * Helper to create Flow without Input.
   * Example usage: From[Int]
   */
  def apply[T]: ProcessorFlow[T, T] = ProcessorFlow[T, T](EmptyTransform[T, T]())

  /**
   * Helper to create Flow with Input from Iterable.
   * Example usage: Flow(Seq(1,2,3))
   */
  def apply[T](i: immutable.Iterable[T]): PublisherFlow[T, T] = From[T].withInput(IterableIn(i))

  /**
   * Helper to create Flow with Input from Future.
   * Example usage: Flow(Future { 1 })
   */
  def apply[T](f: Future[T]): PublisherFlow[T, T] = From[T].withInput(FutureIn(f))

  /**
   * Helper to create Flow with Input from Publisher.
   */
  def apply[T](p: Publisher[T]): PublisherFlow[T, T] = From[T].withInput(PublisherIn(p))
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
sealed trait HasOpenInput[-In, +Out] {
  type Repr[-In, +Out] <: HasOpenInput[In, Out]
  type AfterCloseInput[-In, +Out] <: Flow[In, Out]

  def withInput[I <: In](in: Input[I]): AfterCloseInput[I, Out]
  protected def prependTransform[T](t: Transform[T, In]): Repr[T, Out]

  // linear combinators with flows
  def prepend[T](f: ProcessorFlow[T, In]): Repr[T, Out] =
    prependTransform(f.transform)
  def prepend[T](f: PublisherFlow[T, In]): Repr[T, Out]#AfterCloseInput[T, Out] =
    prependTransform(f.transform).withInput(f.input)
}

/**
 * Operations with a Flow which has open (no attached) Output.
 *
 * No In type parameter would be useful for Graph signatures, but we need it here
 * for `withOutput` and `appendTransform` methods.
 */
trait HasOpenOutput[-In, +Out] {
  type Repr[-In, +Out] <: HasOpenOutput[In, Out]
  type AfterCloseOutput[-In, +Out] <: Flow[In, Out]

  def withOutput[O >: Out](out: Output[O]): AfterCloseOutput[In, O]
  protected def appendTransform[T](t: Transform[Out, T]): Repr[In, T]

  // linear simple combinators
  def map[T](f: Out ⇒ T): Repr[In, T] =
    appendTransform(EmptyTransform[Out, T]())
  def mapFuture[T](f: Out ⇒ Future[T]): Repr[In, T] =
    appendTransform(EmptyTransform[Out, T]())
  def filter(p: Out ⇒ Boolean): Repr[In, Out] =
    appendTransform(EmptyTransform[Out, Out]())
  def collect[T](pf: PartialFunction[Out, T]): Repr[In, T] =
    appendTransform(EmptyTransform[Out, T]())
  def drop(n: Int): Repr[In, Out] =
    appendTransform(EmptyTransform[Out, Out]())
  def dropWithin(d: FiniteDuration): Repr[In, Out] =
    appendTransform(EmptyTransform[Out, Out]())
  def take(n: Int): Repr[In, Out] =
    appendTransform(EmptyTransform[Out, Out]())
  def takeWithin(d: FiniteDuration): Repr[In, Out] =
    appendTransform(EmptyTransform[Out, Out]())
  def grouped(n: Int): Repr[In, immutable.Seq[Out]] =
    appendTransform(EmptyTransform[Out, immutable.Seq[Out]]())
  def groupedWithin(n: Int, d: FiniteDuration): Repr[In, immutable.Seq[Out]] =
    appendTransform(EmptyTransform[Out, immutable.Seq[Out]]())
  def mapConcat[T](f: Out ⇒ immutable.Seq[T]): Repr[In, T] =
    appendTransform(EmptyTransform[Out, T]())
  def transform[T](transformer: Transformer[Out, T]): Repr[In, T] =
    appendTransform(EmptyTransform[Out, T]())
  def conflate[S](seed: Out ⇒ S, aggregate: (S, Out) ⇒ S): Repr[In, S] =
    appendTransform(EmptyTransform[Out, S]())
  def expand[S, O](seed: Out ⇒ S, extrapolate: S ⇒ (O, S)): Repr[In, O] =
    appendTransform(EmptyTransform[Out, O]())
  def buffer(size: Int, overflowStrategy: OverflowStrategy): Repr[In, Out] =
    appendTransform(EmptyTransform[Out, Out]())

  // linear combinators which produce multiple flows
  def prefixAndTail[O >: Out](n: Int): Repr[In, (immutable.Seq[O], PublisherFlow[O, O])] =
    appendTransform(EmptyTransform[Out, (immutable.Seq[O], PublisherFlow[O, O])]())
  def groupBy[O >: Out, K](f: O ⇒ K): Repr[In, (K, PublisherFlow[O, O])] =
    appendTransform(EmptyTransform[Out, (K, PublisherFlow[O, O])]())
  def splitWhen[O >: Out](p: Out ⇒ Boolean): Repr[In, PublisherFlow[O, O]] =
    appendTransform(EmptyTransform[Out, PublisherFlow[O, O]]())

  // linear combinators which consume multiple flows
  def flatten[T](strategy: FlattenStrategy[Out, T]): Repr[In, T] =
    appendTransform(EmptyTransform[Out, T]())

  // linear combinators with flows
  def append[T](f: ProcessorFlow[Out, T]): Repr[In, T] =
    appendTransform(f.transform)
  def append[T](f: SubscriberFlow[Out, T]): Repr[In, T]#AfterCloseOutput[In, T] =
    appendTransform(f.transform).withOutput(f.output)
}

/**
 * Flow without attached input and without attached output, can be used as a `Processor`.
 */
final case class ProcessorFlow[-In, +Out](transform: Transform[In, Out]) extends Flow[In, Out] with HasOpenOutput[In, Out] with HasOpenInput[In, Out] {
  override type Repr[-In, +Out] = ProcessorFlow[In, Out]
  type AfterCloseOutput[-In, +Out] = SubscriberFlow[In, Out]
  type AfterCloseInput[-In, +Out] = PublisherFlow[In, Out]

  def withOutput[O >: Out](out: Output[O]): AfterCloseOutput[In, O] = SubscriberFlow(out, transform)
  def withInput[I <: In](in: Input[I]): AfterCloseInput[I, Out] = PublisherFlow(in, transform)

  protected def prependTransform[T](t: Transform[T, In]): Repr[T, Out] = ProcessorFlow(t ++ transform)
  protected def appendTransform[T](t: Transform[Out, T]): Repr[In, T] = ProcessorFlow(transform ++ t)
}

/**
 *  Flow with attached output, can be used as a `Subscriber`.
 */
final case class SubscriberFlow[-In, +Out](output: Output[Out], transform: Transform[In, Out]) extends Flow[In, Out] with HasOpenInput[In, Out] {
  type Repr[-In, +Out] = SubscriberFlow[In, Out]
  type AfterCloseInput[-In, +Out] = RunnableFlow[In, Out]

  def withInput[I <: In](in: Input[I]): AfterCloseInput[I, Out] = RunnableFlow(in, output, transform)
  def withoutOutput: ProcessorFlow[In, Out] = ProcessorFlow(transform)

  protected def prependTransform[T](t: Transform[T, In]): Repr[T, Out] =
    SubscriberFlow(output, t ++ transform)
}

/**
 * Flow with attached input, can be used as a `Publisher`.
 */
final case class PublisherFlow[-In, +Out](input: Input[In], transform: Transform[In, Out]) extends Flow[In, Out] with HasOpenOutput[In, Out] {
  override type Repr[-In, +Out] = PublisherFlow[In, Out]
  type AfterCloseOutput[-In, +Out] = RunnableFlow[In, Out]

  def withOutput[O >: Out](out: Output[O]): AfterCloseOutput[In, O] = RunnableFlow(input, out, transform)
  def withoutInput: ProcessorFlow[In, Out] = ProcessorFlow(transform)

  protected def appendTransform[T](t: Transform[Out, T]) = PublisherFlow(input, transform ++ t)
}

/**
 * Flow with attached input and output, can be executed.
 */
final case class RunnableFlow[-In, +Out](input: Input[In], output: Output[Out], transform: Transform[In, Out]) extends Flow[In, Out] {
  def withoutOutput: PublisherFlow[In, Out] = PublisherFlow(input, transform)
  def withoutInput: SubscriberFlow[In, Out] = SubscriberFlow(output, transform)

  def run(): Unit = ()
}

trait Transform[-In, +Out] {
  def ++[T](t: Transform[Out, T]): Transform[In, T] = EmptyTransform[In, T]()
}
final case class EmptyTransform[-In, +Out]() extends Transform[In, Out]

object FlattenStrategy {
  def concatPublisherFlow[In, Out]: FlattenStrategy[PublisherFlow[In, Out], Out] = ConcatPublisherFlow[In, Out]()
  def concatProcessorFlow[In, Out]: FlattenStrategy[ProcessorFlow[In, Out], Out] = ConcatProcessorFlow[In, Out]()

  final case class ConcatPublisherFlow[In, Out]() extends FlattenStrategy[PublisherFlow[In, Out], Out]
  final case class ConcatProcessorFlow[In, Out]() extends FlattenStrategy[ProcessorFlow[In, Out], Out]
}
