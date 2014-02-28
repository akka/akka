package akka.streams

import scala.language.{ implicitConversions, higherKinds }

import rx.async.api
import Operation._

trait OperationApiImplicits {
  implicit def producer2Ops1[T](producer: api.Producer[T]) = SourceOps1[T](producer)
  implicit def producerOps2[I, O](op: I ==> api.Producer[O]) = OperationOps2(OperationOps1(op).map(FromProducerSource(_)))

  // TODO: move API-prototype into separate file as it is basically unrelated to the model itself
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
    def expose: Res[api.Producer[B]] = andThen(ExposeProducer())
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
