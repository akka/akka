package akka.stream.dsl

import scala.collection.immutable.Iterable
import scala.concurrent.Future

trait Flow[-In, +Out] {
  val transform: Transform[In, Out]
}

object From {
  /**
   * Helper to create Flow without Input.
   * Example usage: From[Int]
   */
  def apply[T]: OpenFlow[T, T] = OpenFlow[T, T](EmptyTransform[T, T]())

  /**
   * Helper to create Flow with Input from Iterable.
   * Example usage: Flow(Seq(1,2,3))
   */
  def apply[T](i: Iterable[T]): OpenOutputFlow[T, T] = From[T].withInput(IterableIn(i))

  /**
   * Helper to create Flow with Input from Future.
   * Example usage: Flow(Future { 1 })
   */
  def apply[T](f: Future[T]): OpenOutputFlow[T, T] = From[T].withInput(FutureIn(f))
}

trait Input[-In]

/**
 * Input from Iterable
 *
 * Changing In from Contravariant to Covariant is needed because Iterable[+A].
 * But this brakes IterableIn variance and we get IterableIn(Seq(1,2,3)): IterableIn[Any]
 */
case class IterableIn[-In](i: Iterable[_ >: In]) extends Input[In]

/**
 * Input from Future
 *
 * Changing In from Contravariant to Covariant is needed because Future[+A].
 * But this brakes FutureIn variance and we get FutureIn(Future{1}): FutureIn[Any]
 */
case class FutureIn[-In](f: Future[_ >: In]) extends Input[In]

trait Output[+Out]

case class FutureOut[+Out]() extends Output[Out]
case class PublisherOut[+Out]() extends Output[Out]

/**
 * Operations with a Flow which has open (no attached) Input.
 *
 * No Out type parameter would be useful for Graph signatures, but we need it here
 * for `withInput` and `prependTransform` methods.
 */
sealed trait HasOpenInput[-In, +Out] extends Flow[In, Out] {
  type Repr[-I, +O] <: HasOpenInput[I, O]
  type AfterCloseInput[-In, +Out] <: Flow[In, Out]

  def withInput[I <: In](in: Input[I]): AfterCloseInput[I, Out]
  protected def prependTransform[T](t: Transform[T, In]): Repr[T, Out]

  // linear combinators with flows
  def prepend[T](f: OpenFlow[T, In]) =
    prependTransform(f.transform)
  def prependClosed[T](f: OpenOutputFlow[T, In]) =
    prependTransform(f.transform).withInput(f.input)
}

/**
 * Operations with a Flow which has open (no attached) Output.
 *
 * No In type parameter would be useful for Graph signatures, but we need it here
 * for `withOutput` and `appendTransform` methods.
 */
trait HasOpenOutput[-In, +Out] extends Flow[In, Out] {
  type Repr[-I, +O] <: HasOpenOutput[I, O]
  type AfterCloseOutput[-In, +Out] <: Flow[In, Out]

  def withOutput[O >: Out](out: Output[O]): AfterCloseOutput[In, O]
  protected def appendTransform[I <: In, T](t: Transform[I, T]): Repr[I, T]

  // linear simple combinators
  def map[T](f: Out ⇒ T) = appendTransform(transform ++ EmptyTransform[Out, T]())
  def filter(p: Out ⇒ Boolean) = appendTransform(transform ++ EmptyTransform[Out, Out]())

  // linear combinator which produce multiple flows (is this still linear? move to graph?)
  def groupBy[O >: Out, K](f: O ⇒ K): Repr[In, (K, OpenOutputFlow[O, O])] =
    appendTransform(transform ++ EmptyTransform[Out, (K, OpenOutputFlow[O, O])]())

  // linear combinators with flows
  def append[T](f: OpenFlow[Out, T]) =
    appendTransform(transform ++ f.transform)
  def appendClosed[T](f: OpenInputFlow[Out, T]) =
    appendTransform(transform ++ f.transform).withOutput(f.output)

  // terminal combinators
  def ToFuture = withOutput(FutureOut())
  def ToPublisher = withOutput(PublisherOut())
}

case class OpenFlow[-In, +Out](transform: Transform[In, Out]) extends HasOpenOutput[In, Out] with HasOpenInput[In, Out] {
  override type Repr[-I, +O] = OpenFlow[I, O]
  type AfterCloseOutput[-I, +O] = OpenInputFlow[I, O]
  type AfterCloseInput[-I, +O] = OpenOutputFlow[I, O]

  def withOutput[O >: Out](out: Output[O]) = OpenInputFlow(out, transform)
  def withInput[I <: In](in: Input[I]) = OpenOutputFlow(in, transform)

  protected def prependTransform[T](t: Transform[T, In]) = OpenFlow(t ++ transform)
  protected def appendTransform[I <: In, T](t: Transform[I, T]) = OpenFlow(t)
}

case class OpenInputFlow[-In, +Out](output: Output[Out], transform: Transform[In, Out]) extends HasOpenInput[In, Out] {
  type Repr[-I, +O] = OpenInputFlow[I, O]
  type AfterCloseInput[-I, +O] = ClosedFlow[I, O]

  def withoutOutput = OpenFlow(transform)
  def withInput[I <: In](in: Input[I]) = ClosedFlow(in, output, transform)

  protected def prependTransform[T](t: Transform[T, In]) = OpenInputFlow(output, t ++ transform)
}

case class OpenOutputFlow[-In, +Out](input: Input[In], transform: Transform[In, Out]) extends HasOpenOutput[In, Out] {
  override type Repr[-I, +O] = OpenOutputFlow[I, O]
  type AfterCloseOutput[-I, +O] = ClosedFlow[I, O]

  def withOutput[O >: Out](out: Output[O]) = ClosedFlow(input, out, transform)
  def withoutInput = OpenFlow(transform)

  protected def appendTransform[I <: In, T](t: Transform[I, T]) = OpenOutputFlow(input, t)
}

case class ClosedFlow[-In, +Out](input: Input[In], output: Output[Out], transform: Transform[In, Out]) extends Flow[In, Out] {
  def withoutOutput = OpenOutputFlow(input, transform)
  def withoutInput = OpenInputFlow(output, transform)

  def run(): Unit = ()
}

trait Transform[-In, +Out] {
  def ++[T](t: Transform[Out, T]): Transform[In, T] = EmptyTransform[In, T]()
}
case class EmptyTransform[-In, +Out]() extends Transform[In, Out]
