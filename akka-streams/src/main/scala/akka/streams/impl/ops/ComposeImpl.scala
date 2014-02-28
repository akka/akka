package akka.streams.impl.ops

import akka.streams.impl._

object ComposeImpl {
  def pipeline[B](_sourceConstructor: Downstream[B] ⇒ SyncSource, _sinkConstructor: Upstream ⇒ SyncSink[B]): SyncRunnable =
    new AbstractComposeImpl[B] {
      type Source = SyncSource
      type Sink = SyncSink[B]

      def sourceConstructor = _sourceConstructor
      def sinkConstructor = _sinkConstructor
    }

  def source[B](_sourceConstructor: Downstream[B] ⇒ SyncSource, _sinkConstructor: Upstream ⇒ SyncOperation[B]): SyncSource =
    new AbstractComposeImpl[B] with DownOperation[B] {
      type Source = SyncSource

      def sourceConstructor: Downstream[B] ⇒ Source = _sourceConstructor
      def sinkConstructor: Upstream ⇒ Sink = _sinkConstructor
    }
  def sink[A, B](_sourceConstructor: Downstream[B] ⇒ SyncOperation[A], _sinkConstructor: Upstream ⇒ SyncSink[B]): SyncSink[A] =
    new AbstractComposeImpl[B] with UpOperation[A, B] {
      type Sink = SyncSink[B]

      def sourceConstructor: Downstream[B] ⇒ Source = _sourceConstructor
      def sinkConstructor: Upstream ⇒ Sink = _sinkConstructor
    }

  def operation[A, B](_sourceConstructor: Downstream[B] ⇒ SyncOperation[A], _sinkConstructor: Upstream ⇒ SyncOperation[B]): SyncOperation[A] =
    new AbstractComposeImpl[B] with SyncOperation[A] with UpOperation[A, B] with DownOperation[B] {
      def sourceConstructor: Downstream[B] ⇒ Source = _sourceConstructor
      def sinkConstructor: Upstream ⇒ Sink = _sinkConstructor

      override def start(): Effect = sink.start()
    }

  trait UpOperation[A, B] extends AbstractComposeImpl[B] with SyncSink[A] {
    type Source = SyncOperation[A]

    def handleNext(element: A): Effect = handleInternalSourceResult(source.handleNext(element))
    def handleComplete(): Effect = handleInternalSourceResult(source.handleComplete())
    def handleError(cause: Throwable): Effect = handleInternalSourceResult(source.handleError(cause))
  }
  trait DownOperation[B] extends AbstractComposeImpl[B] with SyncSource {
    type Sink = SyncOperation[B]

    def handleRequestMore(n: Int): Effect = handleInternalSinkResult(sink.handleRequestMore(n))
    def handleCancel(): Effect = handleInternalSinkResult(sink.handleCancel())
  }

  abstract class AbstractComposeImpl[T] extends SyncRunnable {
    type Source <: SyncSource
    type Sink <: SyncSink[T]

    def sourceConstructor: Downstream[T] ⇒ Source
    def sinkConstructor: Upstream ⇒ Sink

    lazy val innerDownstream = new Downstream[T] {
      val next: T ⇒ Effect = BasicEffects.HandleNextInSink(sink, _)
      lazy val complete: Effect = BasicEffects.CompleteSink(sink)
      val error: Throwable ⇒ Effect = BasicEffects.HandleErrorInSink(sink, _)
    }
    lazy val innerUpstream = new Upstream {
      val requestMore: Int ⇒ Effect = BasicEffects.RequestMoreFromSource(source, _)
      val cancel: Effect = BasicEffects.CancelSource(source)
    }
    lazy val source: Source = sourceConstructor(innerDownstream)
    lazy val sink: Sink = sinkConstructor(innerUpstream)

    override def start(): Effect = sink.start() ~ source.start()

    // TODO: add shortcuts for at least one direction (or one step)
    def handleInternalSourceResult(result: Effect): Effect = result match {
      //case NextToDown(_, element) ⇒ right.handleNext(element)
      //case CompleteDown(_)       ⇒ right.handleComplete()
      //case ErrorToDown(_, cause) ⇒ right.handleError(cause)
      case x ⇒ x
    }
    def handleInternalSinkResult(result: Effect): Effect = result match {
      //case f: Backward ⇒ f.run()
      //case RequestMoreFromUp(_, n) ⇒ left.handleRequestMore(n)
      //case CancelUp(_)             ⇒ left.handleCancel()
      case x ⇒ x
    }
  }
}
