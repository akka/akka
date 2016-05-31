package akka.stream.testkit

import akka.actor.NoSerializationVerificationNeeded
import akka.stream.scaladsl.Source
import akka.stream.stage.{ OutHandler, GraphStageWithMaterializedValue, InHandler }
import akka.stream._
import akka.testkit.TestProbe

object GraphStageMessages {
  case object Push extends NoSerializationVerificationNeeded
  case object UpstreamFinish extends NoSerializationVerificationNeeded
  case class Failure(ex: Throwable) extends NoSerializationVerificationNeeded

  case object Pull extends NoSerializationVerificationNeeded
  case object DownstreamFinish extends NoSerializationVerificationNeeded
}

object TestSinkStage {
  def apply[T, M](
    stageUnderTest: GraphStageWithMaterializedValue[SinkShape[T], M],
    probe:          TestProbe) = new TestSinkStage(stageUnderTest, probe)
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
        probe.ref ! GraphStageMessages.Push
        inHandler.onPush()
      }
      override def onUpstreamFinish(): Unit = {
        probe.ref ! GraphStageMessages.UpstreamFinish
        inHandler.onUpstreamFinish()
      }
      override def onUpstreamFailure(ex: Throwable): Unit = {
        probe.ref ! GraphStageMessages.Failure(ex)
        inHandler.onUpstreamFailure(ex)
      }
    }
    (logic, mat)
  }
}

object TestSourceStage {
  def apply[T, M](
    stageUnderTest: GraphStageWithMaterializedValue[SourceShape[T], M],
    probe:          TestProbe) = Source.fromGraph(new TestSourceStage(stageUnderTest, probe))
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
        probe.ref ! GraphStageMessages.Pull
        outHandler.onPull()
      }
      override def onDownstreamFinish(): Unit = {
        probe.ref ! GraphStageMessages.DownstreamFinish
        outHandler.onDownstreamFinish()
      }
    }
    (logic, mat)
  }
}