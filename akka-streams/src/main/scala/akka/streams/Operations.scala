package akka.streams

import scala.language.implicitConversions

import asyncrx.api
import api.Consumer
import scala.concurrent.Future
import akka.util.Collections.EmptyImmutableSeq
import scala.collection.immutable

sealed abstract class Operation[-I, +O]

object Operation {
  type ==>[-I, +O] = Operation[I, O] // brevity alias (should we mark it `private`?)

  final case class Pipeline[A](source: Source[A], sink: Sink[A])

  sealed trait Source[+O] {
    def andThen[O2](op: O ==> O2): Source[O2] = MappedSource(this, op)
    def connectTo(sink: Sink[O]): Pipeline[_] =
      sink match {
        // convert MappedSink into MappedSource
        case MappedSink(op, sink) ⇒ andThen(op).connectTo(sink)
        case s                    ⇒ Pipeline(this, s)
      }
  }

  final object Constants {
    val SomeTrue = Some(true)
    val SomeFalse = Some(false)

    private[this] final val _unit = Process.Continue[Nothing, Unit](())
    def ContinueUnit[T]: Process.Continue[T, Unit] = _unit.asInstanceOf[Process.Continue[T, Unit]]
  }

  object Source {
    def empty[T]: Source[T] = EmptySource
    def apply[T](t: T): Source[T] = SingletonSource(t)
    def apply[T](t1: T, t2: T, rest: T*): Source[T] = FromIterableSource(Seq(t1, t2) ++ rest)
    def apply[T](iterable: Iterable[T]): Source[T] = FromIterableSource(iterable)
    def apply[T](producer: api.Producer[T]): Source[T] = FromProducerSource(producer)
    def apply[T](future: Future[T]): Source[T] = FromFutureSource(future)

    implicit def fromIterable[T](iterable: Iterable[T]): Source[T] = FromIterableSource(iterable)
    implicit def fromProducer[T](producer: api.Producer[T]): Source[T] = FromProducerSource(producer)
    implicit def fromFuture[T](future: Future[T]): Source[T] = FromFutureSource(future)
  }

  final case class FromIterableSource[T](iterable: Iterable[T]) extends Source[T]
  final case class SingletonSource[T](element: T) extends Source[T]
  case object EmptySource extends Source[Nothing]
  // TODO: get rid of MappedSource and move operation into Source
  final case class MappedSource[I, O](source: Source[I], operation: Operation[I, O]) extends Source[O] {
    type Input = I
    override def andThen[O2](op: Operation.==>[O, O2]): Source[O2] = MappedSource(source, Operation(operation, op))
  }
  final case class FromProducerSource[T](producer: api.Producer[T]) extends Source[T]
  final case class FromFutureSource[T](future: Future[T]) extends Source[T]
  final case class ConcatSources[T](source1: Source[T], source2: Source[T]) extends Source[T]

  sealed trait Sink[-I] {
    def connectTo[I2 <: I](source: Source[I2]): Pipeline[I2] = Pipeline(source, this)
  }
  // TODO: get rid of MappedSink and push operation into Sink
  final case class MappedSink[I, O](operation: I ==> O, sink: Sink[O]) extends Sink[I]

  implicit def fromConsumer[T](consumer: Consumer[T]) = FromConsumerSink(consumer)
  final case class FromConsumerSink[T](consumer: Consumer[T]) extends Sink[T]

  // this lifts an internal Source into a full-fledged api.Producer
  final case class ExposeProducer[T]() extends (Source[T] ==> api.Producer[T])

  def apply[A, B, C](f: A ==> B, g: B ==> C): A ==> C =
    (f, g) match {
      case (Identity(), _) ⇒ g.asInstanceOf[A ==> C]
      case (_, Identity()) ⇒ f.asInstanceOf[A ==> C]
      case _               ⇒ Compose(f, g)
    }

  // basic operation composition
  // consumes and produces no faster than the respective minimum rates of f and g
  final case class Compose[A, B, C](f: A ==> B, g: B ==> C) extends (A ==> C)

  // adds (bounded or unbounded) pressure elasticity
  // consumes at max rate as long as `canConsume` is true,
  // produces no faster than the rate with which `expand` produces B values
  final case class Buffer[A, B, S](seed: S,
                                   compress: (S, A) ⇒ S,
                                   expand: S ⇒ (S, Option[B]),
                                   canConsume: S ⇒ Boolean) extends (A ==> B)

  // "compresses" a fast upstream by keeping one element buffered and reducing surplus values using the given function
  // consumes at max rate, produces no faster than the upstream
  def Compress[A, B](seed: B, f: (B, A) ⇒ B): A ==> B =
    Buffer[A, B, Either[B, B]]( // Left(b) = we need to request from upstream first, Right(b) = we can dispatch to downstream
      seed = Left(seed),
      compress = (either, a) ⇒ Right(f(either match {
        case Left(x)  ⇒ x
        case Right(x) ⇒ x
      }, a)),
      expand = {
        case x @ Left(_) ⇒ (x, None)
        case Right(b)    ⇒ (Left(b), Some(b))
      },
      canConsume = _ ⇒ true)

  // drops the first n upstream values
  // consumes the first n upstream values at max rate, afterwards directly copies upstream
  def Drop[T](n: Int): T ==> T =
    Process[T, T, Int](
      seed = n,
      onNext = (n, x) ⇒ if (n <= 0) Process.Emit(x, Process.Continue(0)) else Process.Continue(n - 1),
      onComplete = _ ⇒ EmptyImmutableSeq)

  // produces one boolean for the first T that satisfies p
  // consumes at max rate until p(t) becomes true, unsubscribes afterwards
  def Exists[T](p: T ⇒ Boolean): T ==> Boolean =
    MapFind[T, Boolean](x ⇒ if (p(x)) Constants.SomeTrue else None, Constants.SomeFalse)

  // "expands" a slow upstream by buffering the last upstream element and producing it whenever requested
  // consumes at max rate, produces at max rate once the first upstream value has been buffered
  def Expand[T, S](seed: S, produce: S ⇒ (S, T)): T ==> T =
    Buffer[T, T, Option[T]](
      seed = None,
      compress = (_, x) ⇒ Some(x),
      expand = s ⇒ (s, s),
      canConsume = _ ⇒ true)

  // filters a streams according to the given predicate
  // immediately consumes more whenever p(t) is false
  def Filter[T](p: T ⇒ Boolean): T ==> T =
    Process[T, T, Unit](
      seed = (),
      onNext = (_, x) ⇒ if (p(x)) Process.Emit(x, Constants.ContinueUnit) else Constants.ContinueUnit,
      onComplete = _ ⇒ EmptyImmutableSeq)

  // produces the first T that satisfies p
  // consumes at max rate until p(t) becomes true, unsubscribes afterwards
  def Find[T](p: T ⇒ Boolean): T ==> T =
    MapFind[T, T](x ⇒ if (p(x)) Some(x) else None, None)

  // general flatmap operation
  // consumes no faster than the downstream, produces no faster than upstream or generated sources
  final case class FlatMap[A, B](f: A ⇒ Source[B]) extends (A ==> B)

  // flattens the upstream by concatenation
  // consumes no faster than the downstream, produces no faster than the sources in the upstream
  final case class Flatten[T]() extends (Source[T] ==> T)

  // classic fold
  // consumes at max rate, produces only one value
  final case class Fold[A, B](seed: B, f: (B, A) ⇒ B) extends (A ==> B)

  // generalized process potentially producing several output values
  // consumes at max rate as long as `onNext` returns `Continue`
  // produces no faster than the upstream
  //FIXME add a "name" String and fix toString so it is easy to delegate to Process and still retain proper toStrings
  final case class Process[A, B, S](seed: S,
                                    onNext: (S, A) ⇒ Process.Command[B, S],
                                    onComplete: S ⇒ immutable.Seq[B]) extends (A ==> B)
  final object Process {
    sealed trait Command[+T, +S]
    final case class Emit[T, S](value: T, andThen: Command[T, S]) extends Command[T, S]
    final case class Continue[T, S](nextState: S) extends Command[T, S]
    final case object Stop extends Command[Nothing, Nothing]
  }

  // produces one boolean (if all upstream values satisfy p emits true otherwise false)
  // consumes at max rate until p(t) becomes false, unsubscribes afterwards
  def ForAll[T](p: T ⇒ Boolean): T ==> Boolean =
    MapFind[T, Boolean](x ⇒ if (!p(x)) Constants.SomeFalse else None, Constants.SomeTrue)

  // sinks all upstream value into the given function
  // consumes at max rate
  final case class Foreach[T](f: T ⇒ Unit) extends Sink[T]

  // produces the first upstream element, unsubscribes afterwards
  def Head[T](): T ==> T = Take(1)

  final case class SourceHeadTail[A]() extends (Source[A] ==> (A, Source[A]))

  // maps the upstream onto itself
  sealed trait Identity[A] extends (A ==> A)
  final object Identity extends Identity[Nothing] {
    private[this] final val unapplied = Some(this)
    def apply[T](): Identity[T] = this.asInstanceOf[Identity[T]]
    def unapply[I, O](operation: I ==> O): Option[Identity[I]] =
      if (operation eq this) unapplied.asInstanceOf[Option[Identity[I]]] else None
  }

  // maps the given function over the upstream
  // does not affect consumption or production rates
  final case class Map[A, B](f: A ⇒ B) extends (A ==> B)

  // produces the first B returned by f or optionally the given default value
  // consumes at max rate until f returns a Some, unsubscribes afterwards
  def MapFind[A, B](f: A ⇒ Option[B], default: ⇒ Option[B]): A ==> B =
    Process[A, B, Unit](
      seed = (),
      onNext = (_, x) ⇒ f(x) match {
        case Some(value) ⇒ Process.Emit(value, Process.Stop)
        case None        ⇒ Constants.ContinueUnit
      },
      onComplete = _ ⇒ default match {
        case Some(value) ⇒ value :: Nil
        case None        ⇒ EmptyImmutableSeq
      })

  // merges the values produced by the given source into the consumed stream
  // consumes from the upstream and the given source no faster than the downstream
  // produces no faster than the combined rate from upstream and the given source
  final case class Merge[B](source: Source[B]) extends (B ==> B)

  // splits the upstream into sub-streams based on the given predicate
  // if p evaluates to true the current value is appended to the previous sub-stream,
  // otherwise the previous sub-stream is closed and a new one started
  // consumes and produces no faster than the produced sources are consumed
  final case class Span[T](p: T ⇒ Boolean) extends (T ==> Source[T])

  // taps into the upstream and forwards all incoming values also into the given sink
  // consumes no faster than the minimum rate of the downstream and the given sink
  final case class Tee[T](sink: Sink[T]) extends (T ==> T)

  // drops the first upstream value and forwards the remaining upstream
  // consumes the first upstream value immediately, afterwards directly copies upstream
  def Tail[T](): T ==> T = Drop(1)

  // forwards the first n upstream values, unsubscribes afterwards
  // consumes no faster than the downstream, produces no faster than the upstream
  def Take[T](n: Int): T ==> T =
    Process[T, T, Int](
      seed = n,
      onNext = (n, x) ⇒ n match {
        case _ if n <= 0 ⇒ Process.Stop
        case 1           ⇒ Process.Emit(x, Process.Stop)
        case _           ⇒ Process.Emit(x, Process.Continue(n - 1))
      },
      onComplete = _ ⇒ EmptyImmutableSeq)

  //final case class TakeWhile[T](p: T ⇒ Boolean) extends (T ==> T)

  def TakeWhile[T](p: T ⇒ Boolean): T ==> T =
    Process[T, T, Boolean](
      seed = true,
      onNext = (running, next) ⇒ if (running && p(next)) Process.Emit(next, Process.Continue(false)) else Process.Stop,
      onComplete = _ ⇒ EmptyImmutableSeq)

  // combines the upstream and the given source into tuples
  // produces at the rate of the slower upstream (i.e. no values are dropped)
  // consumes from the upstream no faster than the downstream consumption rate or the production rate of the given source
  // consumes from the given source no faster than the downstream consumption rate or the upstream production rate
  final case class Zip[A, B, C](source: Source[C]) extends (A ==> (B, C))
}