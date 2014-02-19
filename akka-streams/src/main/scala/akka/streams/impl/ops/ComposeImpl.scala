package akka.streams.impl.ops

import akka.streams.impl._

object ComposeImpl {
  def pipeline[B](_upstreamConstructor: Downstream[B] ⇒ SyncSource, _downstreamConstructor: Upstream ⇒ SyncSink[B]): SyncRunnable =
    new AbstractComposeImpl[B] {
      type Up = SyncSource
      type Down = SyncSink[B]

      def upstreamConstructor = _upstreamConstructor
      def downstreamConstructor = _downstreamConstructor
    }

  def source[B](_upstreamConstructor: Downstream[B] ⇒ SyncSource, _downstreamConstructor: Upstream ⇒ SyncOperation[B]): SyncSource =
    new AbstractComposeImpl[B] with DownOperation[B] {
      type Up = SyncSource

      def upstreamConstructor: Downstream[B] ⇒ Up = _upstreamConstructor
      def downstreamConstructor: Upstream ⇒ Down = _downstreamConstructor
    }
  def sink[A, B](_upstreamConstructor: Downstream[B] ⇒ SyncOperation[A], _downstreamConstructor: Upstream ⇒ SyncSink[B]): SyncSink[A] =
    new AbstractComposeImpl[B] with UpOperation[A, B] {
      type Down = SyncSink[B]

      def upstreamConstructor: Downstream[B] ⇒ Up = _upstreamConstructor
      def downstreamConstructor: Upstream ⇒ Down = _downstreamConstructor
    }

  def operation[A, B](_upstreamConstructor: Downstream[B] ⇒ SyncOperation[A], _downstreamConstructor: Upstream ⇒ SyncOperation[B]): SyncOperation[A] =
    new AbstractComposeImpl[B] with SyncOperation[A] with UpOperation[A, B] with DownOperation[B] {
      def upstreamConstructor: Downstream[B] ⇒ Up = _upstreamConstructor
      def downstreamConstructor: Upstream ⇒ Down = _downstreamConstructor

      override def start(): Effect = downstreamHandler.start()
    }

  trait UpOperation[A, B] extends AbstractComposeImpl[B] with SyncSink[A] {
    type Up = SyncOperation[A]

    def handleNext(element: A): Effect = handleUpResult(upstreamHandler.handleNext(element))
    def handleComplete(): Effect = handleUpResult(upstreamHandler.handleComplete())
    def handleError(cause: Throwable): Effect = handleUpResult(upstreamHandler.handleError(cause))
  }
  trait DownOperation[B] extends AbstractComposeImpl[B] with SyncSource {
    type Down = SyncOperation[B]

    def handleRequestMore(n: Int): Effect = handleDownResult(downstreamHandler.handleRequestMore(n))
    def handleCancel(): Effect = {
      Thread.dumpStack()
      handleDownResult(downstreamHandler.handleCancel())
    }
  }

  abstract class AbstractComposeImpl[T] extends SyncRunnable {
    type Up <: SyncSource
    type Down <: SyncSink[T]

    def upstreamConstructor: Downstream[T] ⇒ Up
    def downstreamConstructor: Upstream ⇒ Down

    lazy val innerDownstream = new Downstream[T] {
      val next: T ⇒ Effect = BasicEffects.HandleNextInSink(downstreamHandler, _)
      lazy val complete: Effect = BasicEffects.CompleteSink(downstreamHandler)
      val error: Throwable ⇒ Effect = BasicEffects.HandleErrorInSink(downstreamHandler, _)
    }
    lazy val innerUpstream = new Upstream {
      val requestMore: Int ⇒ Effect = BasicEffects.RequestMoreFromSource(upstreamHandler, _)
      def cancel: Effect = BasicEffects.CancelSource(upstreamHandler)
    }
    lazy val upstreamHandler: Up = upstreamConstructor(innerDownstream)
    lazy val downstreamHandler: Down = downstreamConstructor(innerUpstream)

    override def start(): Effect = downstreamHandler.start() ~ upstreamHandler.start()

    // TODO: add shortcuts for at least one direction (or one step)
    def handleUpResult(result: Effect): Effect = result match {
      //case NextToDown(_, element) ⇒ right.handleNext(element)
      //case CompleteDown(_)       ⇒ right.handleComplete()
      //case ErrorToDown(_, cause) ⇒ right.handleError(cause)
      case x ⇒ x
    }
    def handleDownResult(result: Effect): Effect = result match {
      //case f: Backward ⇒ f.run()
      //case RequestMoreFromUp(_, n) ⇒ left.handleRequestMore(n)
      //case CancelUp(_)             ⇒ left.handleCancel()
      case x ⇒ x
    }
  }
}
