package akka.streams
package impl

import org.scalatest.{ ShouldMatchers, FreeSpec }
import Operation._
import rx.async.api.Producer

class SyncOperationIntegrationSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "Simple chains" - {}
  "Complex chains requiring back-forth chatter" - {
    "internal source + map + fold" in {
      val combination = instance(FromIterableSource(1 to 10).map(_ + 1).fold(0f)(_ + _.toFloat))
      val r @ BasicEffects.RequestMoreFromSource(_, 100) = combination.handleRequestMore(1)
      r.runToResult() should be(DownstreamNext(65.0) ~ DownstreamComplete)
    }
    "flatten with generic producer" in pending
    "flatten.map(_ + 1f)" in {
      val combination = instance(Flatten[Float]().map(_ + 1f))
      pending
    }
    "span + flatten == identity" in {
      object Context extends ContextEffects {
        def subscribeTo[O](source: Source[O])(onSubscribe: Upstream ⇒ (SyncSink[O], Effect)): Effect =
          source match {
            case InternalSource(h) ⇒ ContextEffects.subscribeToInternalSource(h, onSubscribe)
          }

        def subscribeFrom[O](sink: Sink[O])(onSubscribe: (Downstream[O]) ⇒ (SyncSource, Effect)): Effect = ???
        def expose[O](source: Source[O]): Producer[O] = ???
      }

      val p = instance[Int](FromIterableSource(1 to 6).span(_ % 3 == 0).flatten, Context)
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(1))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(2))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(3))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(4))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(5))
      p.handleRequestMore(1).runToResult() should be(DownstreamNext(6) ~ DownstreamComplete)
    }
  }

  def instance[O](source: Source[O], ctx: ContextEffects = null): SyncSource =
    OperationImpl(downstream, ctx, source)
  def instance[I](operation: Operation[I, Float]): SyncOperation[I] =
    OperationImpl(upstream, downstream, null, operation)
}
