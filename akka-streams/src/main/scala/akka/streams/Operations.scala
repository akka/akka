package akka.streams

import scala.language.{ implicitConversions, higherKinds }
import rx.async.api.{ Consumer, Producer }
import scala.concurrent.Future

sealed trait Operation[-I, +O]

object Operation {
  type ==>[-I, +O] = Operation[I, O] // brevity alias (should we mark it `private`?)

  case class Pipeline[A](source: Source[A], sink: Sink[A])

  sealed trait Source[+O] {
    def andThen[O2](op: O ==> O2): Source[O2] = MappedSource(this, op)
    def finish(sink: Sink[O]): Pipeline[_] =
      sink match {
        // convert MappedSink into MappedSource
        case MappedSink(op, sink) ⇒ andThen(op).finish(sink)
        case s                    ⇒ Pipeline(this, s)
      }
  }
  object Source {
    def empty[T]: Source[T] = EmptySource
    def apply[T](t: T): Source[T] = SingletonSource(t)
    def apply[T](t1: T, t2: T, rest: T*): Source[T] = FromIterableSource(Seq(t1, t2) ++ rest)
    def apply[T](iterable: Iterable[T]): Source[T] = FromIterableSource(iterable)
    def apply[T](producer: Producer[T]): Source[T] = FromProducerSource(producer)
    def apply[T](future: Future[T]): Source[T] = FromFutureSource(future)

    implicit def fromIterable[T](iterable: Iterable[T]): Source[T] = FromIterableSource(iterable)
    implicit def fromProducer[T](producer: Producer[T]): Source[T] = FromProducerSource(producer)
    implicit def fromFuture[T](future: Future[T]): Source[T] = FromFutureSource(future)
  }
  trait CustomSource[+O] extends Source[O]

  case class FromIterableSource[T](iterable: Iterable[T]) extends Source[T]
  case class SingletonSource[T](element: T) extends Source[T]
  case object EmptySource extends Source[Nothing]
  case class MappedSource[I, O](source: Source[I], operation: Operation[I, O]) extends Source[O] {
    type Input = I
    override def andThen[O2](op: Operation.==>[O, O2]): Source[O2] = MappedSource(source, Operation(operation, op))
  }
  case class FromProducerSource[T](producer: Producer[T]) extends Source[T]
  case class FromFutureSource[T](future: Future[T]) extends Source[T]
  case class ConcatSources[T](source1: Source[T], source2: Source[T]) extends Source[T]

  sealed trait Sink[-I] {
    def finish[I2 <: I](source: Source[I2]): Pipeline[I2] = Pipeline(source, this)
  }
  case class MappedSink[I, O](operation: I ==> O, sink: Sink[O]) extends Sink[I]

  implicit def fromConsumer[T](consumer: Consumer[T]) = FromConsumerSink(consumer)
  case class FromConsumerSink[T](consumer: Consumer[T]) extends Sink[T]

  // this lifts an internal Source into a full-fledged Producer
  case class ExposeProducer[T]() extends (Source[T] ==> Producer[T])

  def apply[A, B, C](f: A ==> B, g: B ==> C): A ==> C =
    (f, g) match {
      case (Identity(), _) ⇒ g.asInstanceOf[A ==> C]
      case (_, Identity()) ⇒ f.asInstanceOf[A ==> C]
      case _               ⇒ Compose(f, g)
    }

  // basic operation composition
  // consumes and produces no faster than the respective minimum rates of f and g
  case class Compose[A, B, C](f: A ==> B, g: B ==> C) extends (A ==> C)

  // adds (bounded or unbounded) pressure elasticity
  // consumes at max rate as long as `canConsume` is true,
  // produces no faster than the rate with which `expand` produces B values
  case class Buffer[A, B, S](seed: S,
                             compress: (S, A) ⇒ S,
                             expand: S ⇒ (S, Option[B]),
                             canConsume: S ⇒ Boolean) extends (A ==> B)

  // "compresses" a fast upstream by keeping one element buffered and reducing surplus values using the given function
  // consumes at max rate, produces no faster than the upstream
  def Compress[A, B](seed: B, f: (B, A) ⇒ B): A ==> B =
    Buffer[A, B, Either[B, B]]( // Left(b) = we need to request from upstream first, Right(b) = we can dispatch to downstream
      seed = Left(seed),
      compress = (either, a) ⇒ Right(f(either.fold(identity, identity), a)),
      expand = {
        case x @ Left(_) ⇒ x -> None
        case Right(b)    ⇒ Left(b) -> Some(b)
      },
      canConsume = _ ⇒ true)

  // drops the first n upstream values
  // consumes the first n upstream values at max rate, afterwards directly copies upstream
  def Drop[T](n: Int): T ==> T =
    Process[T, T, Int](
      seed = n,
      onNext = (n, x) ⇒ if (n <= 0) Process.Emit(x, Process.Continue(0)) else Process.Continue(n - 1),
      onComplete = _ ⇒ Nil)

  // produces one boolean for the first T that satisfies p
  // consumes at max rate until p(t) becomes true, unsubscribes afterwards
  def Exists[T](p: T ⇒ Boolean): T ==> Boolean =
    MapFind[T, Boolean](x ⇒ if (p(x)) Some(true) else None, Some(false))

  // "expands" a slow upstream by buffering the last upstream element and producing it whenever requested
  // consumes at max rate, produces at max rate once the first upstream value has been buffered
  def Expand[T, S](seed: S, produce: S ⇒ (S, T)): T ==> T =
    Buffer[T, T, Option[T]](
      seed = None,
      compress = (_, x) ⇒ Some(x),
      expand = s ⇒ s -> s,
      canConsume = _ ⇒ true)

  // filters a streams according to the given predicate
  // immediately consumes more whenever p(t) is false
  def Filter[T](p: T ⇒ Boolean): T ==> T =
    Process[T, T, Unit](
      seed = (),
      onNext = (_, x) ⇒ if (p(x)) Process.Emit(x, Process.Continue(())) else Process.Continue(()),
      onComplete = _ ⇒ Nil)

  // produces the first T that satisfies p
  // consumes at max rate until p(t) becomes true, unsubscribes afterwards
  def Find[T](p: T ⇒ Boolean): T ==> T =
    MapFind[T, T](x ⇒ if (p(x)) Some(x) else None, None)

  // general flatmap operation
  // consumes no faster than the downstream, produces no faster than upstream or generated sources
  case class FlatMap[A, B](f: A ⇒ Source[B]) extends (A ==> B)

  // flattens the upstream by concatenation
  // consumes no faster than the downstream, produces no faster than the sources in the upstream
  case class Flatten[T]() extends (Source[T] ==> T)

  // classic fold
  // consumes at max rate, produces only one value
  case class Fold[A, B](seed: B, f: (B, A) ⇒ B) extends (A ==> B)

  // generalized process potentially producing several output values
  // consumes at max rate as long as `onNext` returns `Continue`
  // produces no faster than the upstream
  case class Process[A, B, S](seed: S,
                              onNext: (S, A) ⇒ Process.Command[B, S],
                              onComplete: S ⇒ Seq[B]) extends (A ==> B)
  object Process {
    sealed trait Command[+T, +S]
    case class Emit[T, S](value: T, andThen: Command[T, S]) extends Command[T, S]
    case class Continue[T, S](nextState: S) extends Command[T, S]
    case object Stop extends Command[Nothing, Nothing]
  }

  // produces one boolean (if all upstream values satisfy p emits true otherwise false)
  // consumes at max rate until p(t) becomes false, unsubscribes afterwards
  def ForAll[T](p: T ⇒ Boolean): T ==> Boolean =
    MapFind[T, Boolean](x ⇒ if (!p(x)) Some(false) else None, Some(true))

  // sinks all upstream value into the given function
  // consumes at max rate
  case class Foreach[T](f: T ⇒ Unit) extends Sink[T]

  // produces the first upstream element, unsubscribes afterwards
  def Head[T](): T ==> T = Take(1)

  case class SourceHeadTail[A]() extends (Source[A] ==> (A, Source[A]))

  // maps the upstream onto itself
  case class Identity[A]() extends (A ==> A)

  // maps the given function over the upstream
  // does not affect consumption or production rates
  case class Map[A, B](f: A ⇒ B) extends (A ==> B)

  // produces the first B returned by f or optionally the given default value
  // consumes at max rate until f returns a Some, unsubscribes afterwards
  def MapFind[A, B](f: A ⇒ Option[B], default: ⇒ Option[B]): A ==> B =
    Process[A, B, Unit](
      seed = (),
      onNext = (_, x) ⇒ f(x).fold[Process.Command[B, Unit]](Process.Continue(()))(Process.Emit(_, Process.Stop)),
      onComplete = _ ⇒ default.toSeq)

  // merges the values produced by the given source into the consumed stream
  // consumes from the upstream and the given source no faster than the downstream
  // produces no faster than the combined rate from upstream and the given source
  case class Merge[B](source: Source[B]) extends (B ==> B)

  // splits the upstream into sub-streams based on the given predicate
  // if p evaluates to true the current value is appended to the previous sub-stream,
  // otherwise the previous sub-stream is closed and a new one started
  // consumes and produces no faster than the produced sources are consumed
  case class Span[T](p: T ⇒ Boolean) extends (T ==> Source[T])

  // taps into the upstream and forwards all incoming values also into the given sink
  // consumes no faster than the minimum rate of the downstream and the given sink
  case class Tee[T](sink: Sink[T]) extends (T ==> T)

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
      onComplete = _ ⇒ Nil)

  case class TakeWhile[T](p: T ⇒ Boolean) extends (T ==> T)

  // combines the upstream and the given source into tuples
  // produces at the rate of the slower upstream (i.e. no values are dropped)
  // consumes from the upstream no faster than the downstream consumption rate or the production rate of the given source
  // consumes from the given source no faster than the downstream consumption rate or the upstream production rate
  case class Zip[A, B, C](source: Source[C]) extends (A ==> (B, C))

  implicit def producer2Ops1[T](producer: Producer[T]) = SourceOps1[T](producer)
  implicit def producerOps2[I, O](op: I ==> Producer[O]) = OperationOps2(OperationOps1(op).map(FromProducerSource(_)))

  trait Ops1[B] extends Any {
    type Res[_]
    def andThen[C](next: B ==> C): Res[C]

    def buffer[C, S](seed: S)(compress: (S, B) ⇒ S)(expand: S ⇒ (S, Option[C]))(canConsume: S ⇒ Boolean): Res[C] = andThen(Buffer(seed, compress, expand, canConsume))
    def compress[C](seed: C)(f: (C, B) ⇒ C): Res[C] = andThen(Compress(seed, f))
    def drop(n: Int): Res[B] = andThen(Drop(n))
    def exists(p: B ⇒ Boolean): Res[Boolean] = andThen(Exists(p))
    def expand[S](seed: S)(produce: S ⇒ (S, B)): Res[B] = andThen(Expand(seed, produce))
    def filter(p: B ⇒ Boolean): Res[B] = andThen(Filter(p))
    def find(p: B ⇒ Boolean): Res[B] = andThen(Find(p))
    def flatMap[C, S](f: B ⇒ S)(implicit conv: S ⇒ Source[C]): Res[C] = andThen(FlatMap(s ⇒ conv(f(s))))
    def fold[C](seed: C)(f: (C, B) ⇒ C): Res[C] = andThen(Fold(seed, f))
    def forAll(p: B ⇒ Boolean): Res[Boolean] = andThen(ForAll(p))
    def head: Res[B] = andThen(Head())
    def map[C](f: B ⇒ C): Res[C] = andThen(Map(f))
    def mapFind[C](f: B ⇒ Option[C], default: ⇒ Option[C]): Res[C] = andThen(MapFind(f, default))
    def merge[B2 >: B](source: Source[B2]): Res[B2] = andThen(Merge(source))
    def process[S, C](seed: S)(f: (S, B) ⇒ Process.Command[C, S])(onComplete: S ⇒ Seq[C]): Res[C] = andThen(Process(seed, f, onComplete))
    def span(p: B ⇒ Boolean): Res[Source[B]] = andThen(Span(p))
    def tee(sink: Sink[B]): Res[B] = andThen(Tee(sink))
    def tail: Res[B] = andThen(Tail())
    def take(n: Int): Res[B] = andThen(Take[B](n))
    def takeWhile(p: B ⇒ Boolean): Res[B] = andThen(TakeWhile(p))
    def zip[C](source: Source[C]): Res[(B, C)] = andThen(Zip(source))
  }

  implicit class OperationOps1[A, B](val op: A ==> B) extends Ops1[B] {
    type Res[U] = A ==> U

    def andThen[C](op: B ==> C): A ==> C = Operation(this.op, op)
    def foreach(f: B ⇒ Unit): Sink[A] = MappedSink(op, Foreach(f))
  }
  implicit class SourceOps1[B](val source: Source[B]) extends Ops1[B] {
    type Res[U] = Source[U]

    def andThen[C](op: B ==> C): Source[C] = source.andThen(op)
    def foreach(f: B ⇒ Unit): Pipeline[B] = Pipeline(source, Foreach(f))
    def ++(other: Source[B]): Source[B] = ConcatSources(source, other)
  }

  trait Ops2[B] extends Any {
    type Res[_]

    def andThen[C](next: Source[B] ==> C): Res[C]

    def flatten: Res[B] = andThen(Flatten[B]())
    def expose: Res[Producer[B]] = andThen(ExposeProducer())
    def headTail: Res[(B, Source[B])] = andThen(SourceHeadTail())
  }

  implicit class OperationOps2[A, B](val op: A ==> Source[B]) extends Ops2[B] {
    type Res[U] = A ==> U

    def andThen[C](next: Source[B] ==> C): Res[C] = Operation(op, next)
  }
  implicit class SourceOps2[B](val source: Source[Source[B]]) extends Ops2[B] {
    type Res[U] = Source[U]

    def andThen[C](next: Source[B] ==> C): Source[C] = source.andThen(next)
  }
}
