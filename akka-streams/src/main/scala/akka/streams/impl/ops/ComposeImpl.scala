package akka.streams
package impl
package ops

object ComposeImpl {
  def pipeline[B](_leftCons: Downstream[B] ⇒ SyncSource, _rightCons: Upstream ⇒ SyncSink[B]): SyncRunnable =
    new AbstractComposeImpl[B] {
      type Left = SyncSource
      type Right = SyncSink[B]

      def leftCons = _leftCons
      def rightCons = _rightCons
    }

  def source[B](_leftCons: Downstream[B] ⇒ SyncSource, _rightCons: Upstream ⇒ SyncOperation[B]): SyncSource =
    new AbstractComposeImpl[B] with RightOperation[B] {
      type Left = SyncSource

      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons
    }
  def sink[A, B](_leftCons: Downstream[B] ⇒ SyncOperation[A], _rightCons: Upstream ⇒ SyncSink[B]): SyncSink[A] =
    new AbstractComposeImpl[B] with LeftOperation[A, B] {
      type Right = SyncSink[B]

      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons
    }

  def operation[A, B](_leftCons: Downstream[B] ⇒ SyncOperation[A], _rightCons: Upstream ⇒ SyncOperation[B]): SyncOperation[A] =
    new AbstractComposeImpl[B] with SyncOperation[A] with LeftOperation[A, B] with RightOperation[B] {
      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons

      override def start(): Effect = right.start()
    }

  trait LeftOperation[A, B] extends AbstractComposeImpl[B] with SyncSink[A] {
    type Left = SyncOperation[A]

    def handleNext(element: A): Effect = handleLeftResult(left.handleNext(element))
    def handleComplete(): Effect = handleLeftResult(left.handleComplete())
    def handleError(cause: Throwable): Effect = handleLeftResult(left.handleError(cause))
  }
  trait RightOperation[B] extends AbstractComposeImpl[B] with SyncSource {
    type Right = SyncOperation[B]

    def handleRequestMore(n: Int): Effect = handleRightResult(right.handleRequestMore(n))
    def handleCancel(): Effect = handleRightResult(right.handleCancel())
  }

  abstract class AbstractComposeImpl[T] extends SyncRunnable {
    type Left <: SyncSource
    type Right <: SyncSink[T]

    def leftCons: Downstream[T] ⇒ Left
    def rightCons: Upstream ⇒ Right

    lazy val innerDownstream = new Downstream[T] {
      val next: T ⇒ Effect = BasicEffects.HandleNextInSink(right, _)
      lazy val complete: Effect = BasicEffects.CompleteSink(right)
      val error: Throwable ⇒ Effect = BasicEffects.HandleErrorInSink(right, _)
    }
    lazy val innerUpstream = new Upstream {
      val requestMore: Int ⇒ Effect = BasicEffects.RequestMoreFromSource(left, _)
      val cancel: Effect = BasicEffects.CancelSource(left)
    }
    lazy val left: Left = leftCons(innerDownstream)
    lazy val right: Right = rightCons(innerUpstream)

    override def start(): Effect = right.start() ~ left.start()

    // TODO: add shortcuts for at least one direction (or one step)
    def handleLeftResult(result: Effect): Effect = result match {
      //case NextToRight(_, element) ⇒ right.handleNext(element)
      //case CompleteRight(_)       ⇒ right.handleComplete()
      //case ErrorToRight(_, cause) ⇒ right.handleError(cause)
      case x ⇒ x
    }
    def handleRightResult(result: Effect): Effect = result match {
      //case f: Backward ⇒ f.run()
      //case RequestMoreFromLeft(_, n) ⇒ left.handleRequestMore(n)
      //case CancelLeft(_)             ⇒ left.handleCancel()
      case x ⇒ x
    }
  }
}
