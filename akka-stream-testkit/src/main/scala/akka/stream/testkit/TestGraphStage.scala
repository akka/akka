package akka.stream.testkit

import akka.actor.NoSerializationVerificationNeeded
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage.{ GraphStageWithMaterializedValue, InHandler, OutHandler }
import akka.stream._
import akka.testkit.TestProbe

/**
 * Messages emitted after the corresponding `stageUnderTest` methods has been invoked.
 */
object GraphStageMessages {
  case object Push extends NoSerializationVerificationNeeded
  case object UpstreamFinish extends NoSerializationVerificationNeeded
  case class Failure(ex: Throwable) extends NoSerializationVerificationNeeded

  case object Pull extends NoSerializationVerificationNeeded
  case object DownstreamFinish extends NoSerializationVerificationNeeded
}

object TestSinkStage {

  /**
   * Creates a sink out of the `stageUnderTest` that will inform the `probe`
   * of graph stage events and callbacks by sending it the various messages found under
   * [[GraphStageMessages]].
   *
   * This allows for creation of a "normal" stream ending with the sink while still being
   * able to assert internal events.
   */
  def apply[T, M](
    stageUnderTest: GraphStageWithMaterializedValue[SinkShape[T], M],
    probe:          TestProbe): Sink[T, M] = Sink.fromGraph(new TestSinkStage(stageUnderTest, probe))
}

private[testkit] class TestSinkStage[T, M](
  stageUnderTest: GraphStageWithMaterializedValue[SinkShape[T], M],
  probe:          TestProbe)
  extends GraphStageWithMaterializedValue[SinkShape[T], M] {

  val in = Inlet[T]("testSinkStage.in")
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    stageUnderTest.shape.in.id = in.id
    val (logic, mat) = stageUnderTest.createLogicAndMaterializedValue(inheritedAttributes)

    val inHandler = logic.handlers(in.id).asInstanceOf[InHandler]
    logic.handlers(in.id) = new InHandler {
      override def onPush(): Unit = {
        inHandler.onPush()
        probe.ref ! GraphStageMessages.Push
      }
      override def onUpstreamFinish(): Unit = {
        try {
          inHandler.onUpstreamFinish()
        } finally {
          probe.ref ! GraphStageMessages.UpstreamFinish
        }
      }
      override def onUpstreamFailure(ex: Throwable): Unit = {
        try {
          inHandler.onUpstreamFailure(ex)
        } finally {
          probe.ref ! GraphStageMessages.Failure(ex)
        }
      }
    }
    (logic, mat)
  }
}

object TestSourceStage {

  /**
   * Creates a source out of the `stageUnderTest` that will inform the `probe`
   * of graph stage events and callbacks by sending it the various messages found under
   * [[GraphStageMessages]].
   *
   * This allows for creation of a "normal" stream starting with the source while still being
   * able to assert internal events.
   */
  def apply[T, M](
    stageUnderTest: GraphStageWithMaterializedValue[SourceShape[T], M],
    probe:          TestProbe): Source[T, M] =
    Source.fromGraph(new TestSourceStage(stageUnderTest, probe))
}

private[testkit] class TestSourceStage[T, M](
  stageUnderTest: GraphStageWithMaterializedValue[SourceShape[T], M],
  probe:          TestProbe)
  extends GraphStageWithMaterializedValue[SourceShape[T], M] {

  val out = Outlet[T]("testSourceStage.out")
  override val shape: SourceShape[T] = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    stageUnderTest.shape.out.id = out.id
    val (logic, mat) = stageUnderTest.createLogicAndMaterializedValue(inheritedAttributes)

    val outHandler = logic.handlers(out.id).asInstanceOf[OutHandler]
    logic.handlers(out.id) = new OutHandler {
      override def onPull(): Unit = {
        outHandler.onPull()
        probe.ref ! GraphStageMessages.Pull
      }
      override def onDownstreamFinish(): Unit = {
        try {
          outHandler.onDownstreamFinish()
        } finally {
          probe.ref ! GraphStageMessages.DownstreamFinish
        }
      }
    }
    (logic, mat)
  }
}