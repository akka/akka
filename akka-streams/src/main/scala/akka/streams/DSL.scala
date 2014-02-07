package akka.streams

import rx.async.api.Producer

import Operation._

object DSL {
  case class ProcessedProducer[I, O](producer: Producer[I], operation: Operation[I, O]) {
    def consume()(implicit settings: ProcessorSettings): Producer[O] = {
      val processor = OperationProcessor2(operation, settings)
      producer.link(processor)
      processor
    }
    def andThen[O2](next: Operation[O, O2]): ProcessedProducer[I, O2] = ProcessedProducer(producer, Operation(operation, next))
  }

  trait Ops[I0, I] extends Any {
    def andThen[O2](next: Operation[I, O2]): ProcessedProducer[I0, O2]

    def map[O2](f: I ⇒ O2): ProcessedProducer[I0, O2] = andThen(Map(f))
    //def flatMap[O2](f: I ⇒ Producer[O2]): ProcessedProducer[I0, O2] = andThen(FlatMap(f))

    //def foreach(f: I ⇒ Unit): ProcessedProducer[I0, Unit] = andThen(Foreach(f))

    def filter(pred: I ⇒ Boolean): ProcessedProducer[I0, I] = andThen(Filter(pred))

    def fold[Z](z: Z)(acc: (Z, I) ⇒ Z): ProcessedProducer[I0, Z] = andThen(Fold(z, acc))
    //def foldUntil[Z, O2](seed: Z)(onNext: (Z, I) ⇒ FoldUntil.Command[Z, O2], onComplete: Z ⇒ Option[O2]): ProcessedProducer[I0, O2] = andThen(FoldUntil(seed, acc, onComplete))

    //def span(pred: I ⇒ Boolean): ProcessedProducer[I0, Producer[I]] = andThen(Span(pred))
    //def headTail: ProcessedProducer[I0, (I, Producer[I])] = andThen(HeadTail[I]())
  }
  implicit class AddProducerOps[I](val producer: Producer[I]) extends Ops[I, I] {
    def andThen[O2](next: Operation[I, O2]): ProcessedProducer[I, O2] = ProcessedProducer(producer, next)
  }
  implicit class AddProcessedProducerOps[I0, I](val producer: ProcessedProducer[I0, I]) extends Ops[I0, I] {
    def andThen[O2](next: Operation[I, O2]): ProcessedProducer[I0, O2] = producer.andThen(next)
  }
}

