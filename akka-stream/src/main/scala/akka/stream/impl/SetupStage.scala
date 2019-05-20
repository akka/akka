/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream._
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler }

import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

/** Internal Api */
@InternalApi private[stream] final class SetupSinkStage[T, M](factory: (ActorMaterializer, Attributes) => Sink[T, M])
    extends GraphStageWithMaterializedValue[SinkShape[T], Future[M]] {

  private val in = Inlet[T]("SetupSinkStage.in")
  override val shape = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[M]) = {
    val matPromise = Promise[M]
    (createStageLogic(matPromise), matPromise.future)
  }

  private def createStageLogic(matPromise: Promise[M]) = new GraphStageLogic(shape) {
    import SetupStage._

    val subOutlet = new SubSourceOutlet[T]("SetupSinkStage")
    subOutlet.setHandler(delegateToInlet(() => pull(in), () => cancel(in)))
    setHandler(in, delegateToSubOutlet(() => grab(in), subOutlet))

    override def preStart(): Unit = {
      try {
        val sink = factory(ActorMaterializerHelper.downcast(materializer), attributes)

        val mat = Source.fromGraph(subOutlet.source).runWith(sink.withAttributes(attributes))(subFusingMaterializer)
        matPromise.success(mat)
      } catch {
        case NonFatal(ex) =>
          matPromise.failure(ex)
          throw ex
      }
    }
  }

}

/** Internal Api */
@InternalApi private[stream] final class SetupFlowStage[T, U, M](
    factory: (ActorMaterializer, Attributes) => Flow[T, U, M])
    extends GraphStageWithMaterializedValue[FlowShape[T, U], Future[M]] {

  private val in = Inlet[T]("SetupFlowStage.in")
  private val out = Outlet[U]("SetupFlowStage.out")
  override val shape = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[M]) = {
    val matPromise = Promise[M]
    (createStageLogic(matPromise), matPromise.future)
  }

  private def createStageLogic(matPromise: Promise[M]) = new GraphStageLogic(shape) {
    import SetupStage._

    val subInlet = new SubSinkInlet[U]("SetupFlowStage")
    val subOutlet = new SubSourceOutlet[T]("SetupFlowStage")

    subInlet.setHandler(delegateToOutlet(push(out, _: U), () => complete(out), fail(out, _), subInlet))
    subOutlet.setHandler(delegateToInlet(() => pull(in), () => cancel(in)))

    setHandler(in, delegateToSubOutlet(() => grab(in), subOutlet))
    setHandler(out, delegateToSubInlet(subInlet))

    override def preStart(): Unit = {
      try {
        val flow = factory(ActorMaterializerHelper.downcast(materializer), attributes)

        val mat = Source
          .fromGraph(subOutlet.source)
          .viaMat(flow.withAttributes(attributes))(Keep.right)
          .to(Sink.fromGraph(subInlet.sink))
          .run()(subFusingMaterializer)
        matPromise.success(mat)
      } catch {
        case NonFatal(ex) =>
          matPromise.failure(ex)
          throw ex
      }
    }
  }
}

/** Internal Api */
@InternalApi private[stream] final class SetupSourceStage[T, M](
    factory: (ActorMaterializer, Attributes) => Source[T, M])
    extends GraphStageWithMaterializedValue[SourceShape[T], Future[M]] {

  private val out = Outlet[T]("SetupSourceStage.out")
  override val shape = SourceShape(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[M]) = {
    val matPromise = Promise[M]
    (createStageLogic(matPromise), matPromise.future)
  }

  private def createStageLogic(matPromise: Promise[M]) = new GraphStageLogic(shape) {
    import SetupStage._

    val subInlet = new SubSinkInlet[T]("SetupSourceStage")
    subInlet.setHandler(delegateToOutlet(push(out, _: T), () => complete(out), fail(out, _), subInlet))
    setHandler(out, delegateToSubInlet(subInlet))

    override def preStart(): Unit = {
      try {
        val source = factory(ActorMaterializerHelper.downcast(materializer), attributes)

        val mat = source.withAttributes(attributes).to(Sink.fromGraph(subInlet.sink)).run()(subFusingMaterializer)
        matPromise.success(mat)
      } catch {
        case NonFatal(ex) =>
          matPromise.failure(ex)
          throw ex
      }
    }
  }
}

private object SetupStage {
  def delegateToSubOutlet[T](grab: () => T, subOutlet: GraphStageLogic#SubSourceOutlet[T]) = new InHandler {
    override def onPush(): Unit =
      subOutlet.push(grab())
    override def onUpstreamFinish(): Unit =
      subOutlet.complete()
    override def onUpstreamFailure(ex: Throwable): Unit =
      subOutlet.fail(ex)
  }

  def delegateToOutlet[T](
      push: T => Unit,
      complete: () => Unit,
      fail: Throwable => Unit,
      subInlet: GraphStageLogic#SubSinkInlet[T]) = new InHandler {
    override def onPush(): Unit =
      push(subInlet.grab())
    override def onUpstreamFinish(): Unit =
      complete()
    override def onUpstreamFailure(ex: Throwable): Unit =
      fail(ex)
  }

  def delegateToSubInlet[T](subInlet: GraphStageLogic#SubSinkInlet[T]) = new OutHandler {
    override def onPull(): Unit =
      subInlet.pull()
    override def onDownstreamFinish(): Unit =
      subInlet.cancel()
  }

  def delegateToInlet(pull: () => Unit, cancel: () => Unit) = new OutHandler {
    override def onPull(): Unit =
      pull()
    override def onDownstreamFinish(): Unit =
      cancel()
  }
}
