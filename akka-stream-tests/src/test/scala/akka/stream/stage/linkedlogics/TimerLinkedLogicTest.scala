package akka.stream.stage.linkedlogic

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{ GraphDSL, RunnableGraph }
import akka.stream.stage.linkedlogic.logics.{ InputLogic, OnceScheduledTimerLogic, PeriodicallyTimerLogic }
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.testkit.{ ImplicitSender, TestKit }
import org.scalatest.{ BeforeAndAfterAll, FlatSpecLike, Matchers }

import scala.concurrent.Await
import scala.concurrent.duration._

class TimerLinkedLogicTest(sys: ActorSystem) extends TestKit(sys)
  with ImplicitSender with Matchers with FlatSpecLike with BeforeAndAfterAll {

  def this() = this(ActorSystem("TimerLinkedLogicTest"))

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

  behavior of "TimerLinkedLogic"

  it should "execute periodically timer" in {
    val (in1, out1, in2, out2) = materialize(new PeriodicallyTimerStage(FiniteDuration(1, TimeUnit.SECONDS)))

    out1.ensureSubscription()

    out1.request(3)
    out2.request(3)
    out1.expectNext("0-1")
    out2.expectNext("0-2")
    out1.expectNext("1-1")
    out2.expectNext("1-2")
    out1.expectNext("2-1")
    out2.expectNext("2-2")
  }

  it should "cancel periodically timer" in {
    val (in1, out1, in2, out2) = materialize(new PeriodicallyTimerStage(FiniteDuration(1, TimeUnit.SECONDS)))

    out1.ensureSubscription()

    out1.request(3)
    out2.request(3)
    out1.expectNext("0-1")
    out2.expectNext("0-2")
    out1.expectNext("1-1")
    out2.expectNext("1-2")
    in1.sendNext("cancel")

    out1.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
    out2.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "do not execute timer with back-pressure condition" in {
    val (in1, out1, in2, out2) = materialize(new PeriodicallyTimerStage(FiniteDuration(1, TimeUnit.SECONDS)))

    out1.ensureSubscription()

    out1.requestNext("0-1")
    out1.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  it should "add and execute timer during execution of logic" in {
    val (in1, out1, in2, out2) = materialize(new EventDrivenCreationOfTimerStage(FiniteDuration(1, TimeUnit.SECONDS)))

    in1.ensureSubscription()
    out1.ensureSubscription()

    in1.sendNext("0")
    out1.requestNext("0-1")
    out2.requestNext("0-2")
    out1.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS))
  }

  class PeriodicallyTimerStage(period: FiniteDuration)(implicit system: ActorSystem, materializer: Materializer) extends GraphStage[BidiShape[String, String, String, String]] {
    override val shape = new BidiShape(Inlet[String]("In1"), Outlet[String]("Out1"), Inlet[String]("In2"), Outlet[String]("Out2"))

    var sequence = 0

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      val logics = new TimerLinkedLogics(shape)
      val timer = logics.add(new PeriodicallyTimerLogic(period) {
        val out1 = linkOutput(shape.out1)
        val out2 = linkOutput(shape.out2)

        override def timerHandler(): Unit = {
          out1.push(sequence.toString + "-1")
          out2.push(sequence.toString + "-2")
          sequence += 1
        }
      })
      logics.add(new InputLogic(shape.in1) {
        override def inputHandler(data: String): Unit = {
          timer.cancel()
        }
      })
      logics.add(new InputLogic(shape.in2) {
        override def inputHandler(data: String): Unit = {}
      })
      logics
    }
  }

  class EventDrivenCreationOfTimerStage(period: FiniteDuration)(implicit system: ActorSystem, materializer: Materializer) extends GraphStage[BidiShape[String, String, String, String]] {
    override val shape = new BidiShape(Inlet[String]("In1"), Outlet[String]("Out1"), Inlet[String]("In2"), Outlet[String]("Out2"))

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      val logics = new TimerLinkedLogics(shape)
      logics.add(new InputLogic(shape.in1) {
        override def inputHandler(data: String): Unit = {
          val timerLogic = logics.add(new OnceScheduledTimerLogic(period) {
            val out1 = linkOutput(shape.out1)
            val out2 = linkOutput(shape.out2)
            override def timerHandler(): Unit = {
              out1.push(data + "-1")
              out2.push(data + "-2")
            }
          })
        }
      })
      logics.add(new InputLogic(shape.in2) {
        override def inputHandler(data: String): Unit = {}
      })
      logics
    }
  }

  def materialize(stage: GraphStage[BidiShape[String, String, String, String]]) = {
    val in1 = TestSource.probe[String]
    val out1 = TestSink.probe[String]
    val in2 = TestSource.probe[String]
    val out2 = TestSink.probe[String]

    val (out_) =
      RunnableGraph.fromGraph(GraphDSL.create(in1, out1, in2, out2)((_, _, _, _)) { implicit builder ⇒ (in1, out1, in2, out2) ⇒
        import GraphDSL.Implicits._

        val ports = builder.add(stage)

        in1 ~> ports.in1
        ports.out1 ~> out1

        in2 ~> ports.in2
        ports.out2 ~> out2

        ClosedShape
      }).run()
    out_
  }
}
