package akka.stream.stage.linkedlogic

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{ GraphDSL, RunnableGraph }
import akka.stream.stage.linkedlogic.logics.{ InputLogic, OutputLink }
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.testkit.{ ImplicitSender, TestKit }
import org.scalatest.{ BeforeAndAfterAll, FlatSpecLike, Matchers }

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class LinkedLogicTest(sys: ActorSystem) extends TestKit(sys)
  with ImplicitSender with Matchers with FlatSpecLike with BeforeAndAfterAll {

  def this() = this(ActorSystem("LinkedLogicTest"))

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(system)
      .withInputBuffer(initialSize = 1, maxSize = 1)
  )

  val log = sys.log

  override def afterAll: Unit = {
    sys.terminate()
    Await.result(sys.whenTerminated, FiniteDuration.apply(10, TimeUnit.SECONDS))
  }

  val maxBufferSize = 10

  behavior of "LinkedLogic"

  it should "pass through hard link" in {
    val (in, out) = materializeFlow(new FlowStage(None))

    out.ensureSubscription()
    in.ensureSubscription()

    in.sendNext("dummy1")
    out.requestNext("dummy1")
    in.sendNext("dummy2")
    out.requestNext("dummy2")

    in.expectRequest()
    in.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "back pressure on hard link" in {
    val (in, out) = materializeFlow(new FlowStage(None))

    out.ensureSubscription()
    in.ensureSubscription()

    in.expectRequest()
    in.sendNext("dummy1")

    in.expectRequest()
    in.sendNext("dummy2")

    in.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "buffer data on buffered link" in {
    val (in, out) = materializeFlow(new FlowStage(Some(BufferInfo(maxBufferSize, OverflowStrategy.backpressure))))

    out.ensureSubscription()
    in.ensureSubscription()

    for (i ← 0 until maxBufferSize) {
      in.expectRequest()
      in.sendNext(i.toString)
    }

    for (i ← 0 until maxBufferSize) {
      out.requestNext(i.toString)
    }
  }

  it should "back pressure on buffered link when buffer is full" in {
    val (in, out) = materializeFlow(new FlowStage(Some(BufferInfo(maxBufferSize, OverflowStrategy.backpressure))))

    out.ensureSubscription()
    in.ensureSubscription()

    for (i ← 0 until maxBufferSize) {
      in.sendNext(i.toString)
    }

    in.expectRequest()
    in.sendNext("dummy")

    in.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "complete flow when buffer overflow with fail strategy" in {
    val (in, out) = materializeFlow(new FlowStage(Some(BufferInfo(maxBufferSize, OverflowStrategy.fail))))

    out.ensureSubscription()

    for (i ← 0 until maxBufferSize) {
      in.sendNext(i.toString)
    }
    out.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))

    in.expectRequest()
    in.sendNext("dummy")

    out.expectComplete()
  }

  it should "add/remove link after start of stage" in {
    val (in, out1, out2) = materializeFanOut(new TemporaryLinkStage())

    in.ensureSubscription()
    out1.ensureSubscription()
    out2.ensureSubscription()

    in.sendNext("dummy1")
    out1.requestNext("dummy1")
    out2.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))

    in.sendNext("add")

    in.sendNext("dummy2")
    in.sendNext("dummy3")
    in.sendNext("dummy4")
    in.sendNext("dummy5")

    in.sendNext("remove")

    out1.requestNext("dummy2")
    out2.requestNext("dummy2")
    out1.requestNext("dummy3")
    out2.requestNext("dummy3")
    out1.requestNext("dummy4")
    out2.requestNext("dummy4")
    out1.requestNext("dummy5")
    out2.requestNext("dummy5")

    in.sendNext("dummy6")
    out1.requestNext("dummy6")

    out2.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
    out1.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "add/remove stopper" in {
    val (in1, in2, out) = materializeFanIn(new StopperLinkStage())

    in1.ensureSubscription()
    in2.ensureSubscription()
    out.ensureSubscription()

    in1.sendNext("dummy1")
    out.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))

    in2.sendNext("start")
    out.requestNext("dummy1")

    in2.sendNext("stop")
    in1.sendNext("dummy2")
    out.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))

    in2.sendNext("start")

    out.requestNext("dummy2")

    in1.sendNext("dummy3")
    out.requestNext("dummy3")

    out.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "back pressure on fan-out links" in {
    val (in, out1, out2) = materializeFanOut(new FanOutStage(None, None))

    in.ensureSubscription()
    out1.ensureSubscription()
    out2.ensureSubscription()

    in.expectRequest()
    in.sendNext("dummy1")

    in.expectRequest()
    in.sendNext("dummy2")

    in.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "back pressure and buffer on fan-out links" in {
    val (in, out1, out2) = materializeFanOut(new FanOutStage(None, Some(BufferInfo(maxBufferSize, OverflowStrategy.backpressure))))

    in.ensureSubscription()
    out1.ensureSubscription()
    out2.ensureSubscription()

    in.expectRequest()
    in.sendNext("dummy1")

    in.expectRequest()
    in.sendNext("dummy2")

    in.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))

    out1.requestNext("dummy1")

    in.expectRequest()
    in.sendNext("dummy3")

    out1.requestNext("dummy2")
    out1.requestNext("dummy3")
    out2.requestNext("dummy1")
    out2.requestNext("dummy2")
    out2.requestNext("dummy3")
  }

  it should "back pressure on fan-in links" in {
    val (in1, in2, out) = materializeFanIn(new FanInStage(None, None))

    in1.ensureSubscription()
    in2.ensureSubscription()
    out.ensureSubscription()

    in1.expectRequest()
    in1.sendNext("dummy1")

    in1.expectRequest()
    in1.sendNext("dummy2")

    in1.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "back pressure and buffer on fan-in links" in {
    val (in1, in2, out) = materializeFanIn(new FanInStage(None, Some(BufferInfo(maxBufferSize, OverflowStrategy.backpressure))))

    in1.ensureSubscription()
    in2.ensureSubscription()
    out.ensureSubscription()

    in1.expectRequest()
    in1.sendNext("dummy1-1")

    in1.expectRequest()
    in1.sendNext("dummy1-2")

    in1.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))

    in2.expectRequest()
    in2.sendNext("dummy2-1")

    in2.expectRequest()
    in2.sendNext("dummy2-2")

    in2.expectRequest()
    in2.sendNext("dummy2-3")

    in2.expectRequest()

    out.requestNext("dummy1-1")
    out.requestNext("dummy2-1")
    out.requestNext("dummy2-2")
    out.requestNext("dummy2-3")
    out.requestNext("dummy1-2")
  }

  it should "order packets on buffered fan-in links" in {
    val (in1, in2, out) = materializeFanIn(new FanInStage(
      Some(BufferInfo(maxBufferSize, OverflowStrategy.backpressure)),
      Some(BufferInfo(maxBufferSize, OverflowStrategy.backpressure))))

    in1.ensureSubscription()
    in2.ensureSubscription()
    out.ensureSubscription()

    in1.expectRequest()
    in1.sendNext("dummy1-1")

    in2.expectRequest()
    in2.sendNext("dummy2-1")

    in2.expectRequest()
    in2.sendNext("dummy2-2")

    in1.expectRequest()
    in1.sendNext("dummy1-2")

    in1.expectRequest()
    in1.sendNext("dummy1-3")

    in2.expectRequest()
    in2.sendNext("dummy2-3")

    in1.expectRequest()
    in2.expectRequest()

    out.requestNext("dummy1-1")
    out.requestNext("dummy2-1")
    out.requestNext("dummy2-2")
    out.requestNext("dummy1-2")
    out.requestNext("dummy1-3")
    out.requestNext("dummy2-3")
  }

  val flowShape = new FlowShape(Inlet[String]("In"), Outlet[String]("Out"))
  val fanOutShape = new FanOutShape(Inlet[String]("In1"), Outlet[String]("Out1"), Outlet[String]("Out2"))
  val fanInShape = new FanInShape(Inlet[String]("In1"), Inlet[String]("In2"), Outlet[String]("Out"))

  case class FanOutShape(in1: Inlet[String], out1: Outlet[String], out2: Outlet[String]) extends Shape {
    override def inlets: immutable.Seq[Inlet[String]] = immutable.Seq(in1)
    override def outlets: immutable.Seq[Outlet[String]] = immutable.Seq(out1, out2)
    override def deepCopy(): Shape = new FanOutShape(in1.carbonCopy(), out1.carbonCopy(), out2.carbonCopy())
  }

  case class FanInShape(in1: Inlet[String], in2: Inlet[String], out: Outlet[String]) extends Shape {
    override def inlets: immutable.Seq[Inlet[String]] = immutable.Seq(in1, in2)
    override def outlets: immutable.Seq[Outlet[String]] = immutable.Seq(out)
    override def deepCopy(): Shape = new FanInShape(in1.carbonCopy(), in2.carbonCopy(), out.carbonCopy())
  }

  case class BufferInfo(size: Int, overflowStrategy: OverflowStrategy)

  class FlowStage(buffer: Option[BufferInfo])(implicit system: ActorSystem, materializer: Materializer) extends GraphStage[FlowShape[String, String]] {
    override def shape = flowShape

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      val logics = new LinkedLogics(shape)
      logics.add(new InputLogic(shape.in) {
        val out = buffer match {
          case Some(buffer) ⇒
            linkOutput(shape.out, buffer.size, buffer.overflowStrategy)
          case None ⇒
            linkOutput(shape.out)
        }

        override def inputHandler(packet: String): Unit = {
          out.push(packet)
        }
      })
      logics
    }
  }

  class TemporaryLinkStage()(implicit system: ActorSystem, materializer: Materializer) extends GraphStage[FanOutShape] {
    override def shape = fanOutShape

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      val logics = new LinkedLogics(shape)
      logics.add(new InputLogic(shape.in1) {
        val out1 = linkOutput(shape.out1, 10, OverflowStrategy.backpressure)
        var out2 = Option.empty[OutputLink[String]]

        override def inputHandler(packet: String): Unit = {
          if (packet == "add") {
            out2 = Some(linkOutput(shape.out2, 10, OverflowStrategy.backpressure))
          } else if (packet == "remove") {
            out2.foreach(_.remove())
            out2 = None
          } else {
            out1.push(packet)
            out2.foreach(_.push(packet))
          }
        }
      })
      logics
    }
  }

  class StopperLinkStage()(implicit system: ActorSystem, materializer: Materializer) extends GraphStage[FanInShape] {
    override def shape = fanInShape

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      val logics = new LinkedLogics(shape)
      val logic1 = new InputLogic(shape.in1) {
        val out = linkOutput(shape.out)

        override def inputHandler(packet: String): Unit = {
          out.push(packet)
        }
      }
      logics.add(logic1)
      var stopper = Some(logic1.linkStopper())
      val logic2 = new InputLogic(shape.in2) {
        override def inputHandler(packet: String): Unit = {
          if (packet == "stop") {
            stopper = Some(logic1.linkStopper())
          } else if (packet == "start") {
            stopper.foreach(_.remove())
          }
        }
      }
      logics.add(logic2)
      logics
    }
  }

  class FanOutStage(buffer1: Option[BufferInfo], buffer2: Option[BufferInfo])(implicit system: ActorSystem, materializer: Materializer) extends GraphStage[FanOutShape] {
    override def shape = fanOutShape

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      val logics = new LinkedLogics(shape)
      if (buffer1 == buffer2) {
        logics.add(new InputLogic(shape.in1) {
          val outs = buffer1 match {
            case Some(buffer) ⇒
              linkOutputs(Seq(shape.out1, shape.out2), buffer.size, buffer.overflowStrategy)
            case None ⇒
              linkOutputs(Seq(shape.out1, shape.out2))
          }

          override def inputHandler(packet: String): Unit = {
            outs(0).push(packet)
            outs(1).push(packet)
          }
        })
      } else {
        logics.add(new InputLogic(shape.in1) {
          val out1 = buffer1 match {
            case Some(buffer) ⇒
              linkOutput(shape.out1, buffer.size, buffer.overflowStrategy)
            case None ⇒
              linkOutput(shape.out1)
          }
          val out2 = buffer2 match {
            case Some(buffer) ⇒
              linkOutput(shape.out2, buffer.size, buffer.overflowStrategy)
            case None ⇒
              linkOutput(shape.out2)
          }

          override def inputHandler(packet: String): Unit = {
            out1.push(packet)
            out2.push(packet)
          }
        })
      }
      logics
    }
  }

  class FanInStage(buffer1: Option[BufferInfo], buffer2: Option[BufferInfo])(implicit system: ActorSystem, materializer: Materializer) extends GraphStage[FanInShape] {
    override def shape = fanInShape

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      val logics = new LinkedLogics(shape)
      logics.add(new InputLogic(shape.in1) {
        val out = buffer1 match {
          case Some(buffer) ⇒
            linkOutput(shape.out, buffer.size, buffer.overflowStrategy)
          case None ⇒
            linkOutput(shape.out)
        }

        override def inputHandler(packet: String): Unit = {
          out.push(packet)
        }
      })
      logics.add(new InputLogic(shape.in2) {
        val out = buffer2 match {
          case Some(buffer) ⇒
            linkOutput(shape.out, buffer.size, buffer.overflowStrategy)
          case None ⇒
            linkOutput(shape.out)
        }

        override def inputHandler(packet: String): Unit = {
          out.push(packet)
        }
      })
      logics
    }
  }

  def materializeFlow(stage: GraphStage[FlowShape[String, String]]) = {
    val in = TestSource.probe[String]
    val out = TestSink.probe[String]

    val (in_, out_) =
      RunnableGraph.fromGraph(GraphDSL.create(in, out)((_, _)) { implicit builder ⇒ (in, out) ⇒
        import GraphDSL.Implicits._

        val ports = builder.add(stage)

        in ~> ports.in
        ports.out ~> out

        ClosedShape
      }).run()
    (in_, out_)
  }

  def materializeFanOut(stage: GraphStage[FanOutShape]) = {
    val in1 = TestSource.probe[String]
    val out1 = TestSink.probe[String]
    val out2 = TestSink.probe[String]

    val (in1_, out1_, out2_) =
      RunnableGraph.fromGraph(GraphDSL.create(in1, out1, out2)((_, _, _)) { implicit builder ⇒ (in1, out1, out2) ⇒
        import GraphDSL.Implicits._

        val ports = builder.add(stage)

        in1 ~> ports.in1

        ports.out1 ~> out1
        ports.out2 ~> out2

        ClosedShape
      }).run()
    (in1_, out1_, out2_)
  }

  def materializeFanIn(stage: GraphStage[FanInShape]) = {
    val in1 = TestSource.probe[String]
    val in2 = TestSource.probe[String]
    val out = TestSink.probe[String]

    val (in1_, in2_, out_) =
      RunnableGraph.fromGraph(GraphDSL.create(in1, in2, out)((_, _, _)) { implicit builder ⇒ (in1, in2, out) ⇒
        import GraphDSL.Implicits._

        val ports = builder.add(stage)

        in1 ~> ports.in1
        in2 ~> ports.in2

        ports.out ~> out

        ClosedShape
      }).run()
    (in1_, in2_, out_)
  }
}
