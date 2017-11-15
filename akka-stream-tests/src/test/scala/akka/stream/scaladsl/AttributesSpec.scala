/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.Attributes._
import akka.stream._
import akka.stream.stage._
import akka.stream.testkit._
import com.typesafe.config.ConfigFactory

object AttributesSpec {

  class AttributesSource(_initialAttributes: Attributes = Attributes.none) extends GraphStage[SourceShape[Attributes]] {
    val out = Outlet[Attributes]("out")
    override protected def initialAttributes: Attributes = _initialAttributes
    override val shape = SourceShape.of(out)
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        def onPull(): Unit = {
          push(out, inheritedAttributes)
          completeStage()
        }
      })
    }
  }

  class AttributesFlow(_initialAttributes: Attributes = Attributes.none) extends GraphStageWithMaterializedValue[FlowShape[Any, Any], Attributes] {

    val in = Inlet[Any]("in")
    val out = Outlet[Any]("out")

    override protected def initialAttributes: Attributes = _initialAttributes
    override val shape = FlowShape(in, out)
    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Attributes) = {
      val logic = new GraphStageLogic(shape) {

        setHandlers(in, out, new InHandler with OutHandler {
          override def onPush(): Unit = push(out, grab(in))
          override def onPull(): Unit = pull(in)
        })
      }

      (logic, inheritedAttributes)
    }
  }

  class AttributesSink(_initialAttributes: Attributes = Attributes.none) extends GraphStageWithMaterializedValue[SinkShape[Any], Attributes] {

    val in = Inlet[Any]("in")

    override protected def initialAttributes: Attributes = _initialAttributes
    override val shape = SinkShape(in)
    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Attributes) = {
      val logic = new GraphStageLogic(shape) {
        override def preStart(): Unit = {
          pull(in)
        }
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            grab(in)
            pull(in)
          }
        })
      }

      (logic, inheritedAttributes)
    }
  }

  class ThreadNameSnitchingStage(initialDispatcher: String) extends GraphStage[SourceShape[String]] {
    val out = Outlet[String]("out")
    override val shape = SourceShape.of(out)
    override protected def initialAttributes: Attributes = ActorAttributes.dispatcher(initialDispatcher)
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        def onPull(): Unit = {
          push(out, Thread.currentThread.getName)
          completeStage()
        }
      })
    }

  }
}

class AttributesSpec extends StreamSpec(ConfigFactory.parseString(
  """
    my-dispatcher {
      type = Dispatcher
      executor = "thread-pool-executor"
      thread-pool-executor {
        fixed-pool-size = 1
      }
      throughput = 1
    }
  """).withFallback(Utils.UnboundedMailboxConfig)) {
  import AttributesSpec._

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "attributes" must {

    val attributes = Attributes.name("a") and Attributes.name("b") and Attributes.inputBuffer(1, 2)

    "give access to first attribute" in {
      attributes.getFirst[Name] should ===(Some(Attributes.Name("a")))
    }

    "give access to attribute byt type" in {
      attributes.get[Name] should ===(Some(Attributes.Name("b")))
    }

  }

  "attributes on a stage" must {

    "be overridable on a module basis" in {
      val attributes =
        Source.fromGraph(new AttributesSource().withAttributes(Attributes.name("new-name")))
          .runWith(Sink.head)
          .futureValue
      attributes.get[Name] should contain(Name("new-name"))
    }

    "keep the outermost attribute as the least specific" in {
      val attributes = Source.fromGraph(new AttributesSource(Attributes.name("original-name")))
        .map(identity)
        .addAttributes(Attributes.name("whole-graph"))
        .runWith(Sink.head)
        .futureValue

      // most specific
      attributes.get[Name] should contain(Name("original-name"))

      // least specific
      attributes.getFirst[Name] should contain(Name("whole-graph"))
    }

    "replace the attributes directly on a graph stage" in {
      val attributes =
        Source.fromGraph(
          new AttributesSource(Attributes.name("original-name")).withAttributes(Attributes.name("new-name"))
        )
          .runWith(Sink.head)
          .futureValue

      // most specific
      attributes.get[Name] should contain(Name("new-name"))

      // least specific
      attributes.getFirst[Name] should contain(Name("new-name"))
    }

    "make the attribues on Source.fromGraph source behave the same as the stage itself" in {
      val attributes =
        Source.fromGraph(new AttributesSource(Attributes.name("original-name")))
          .withAttributes(Attributes.name("replaced")) // this actually replaces now
          .toMat(Sink.head)(Keep.right).withAttributes(Attributes.name("whole-graph"))
          .run()
          .futureValue

      // most specific
      attributes.get[Name] should contain(Name("replaced"))

      // least specific
      attributes.getFirst[Name] should contain(Name("whole-graph"))
    }

    "make the attribues on Flow.fromGraph source behave the same as the stage itself" in {
      val attributes =
        Source.maybe
          .viaMat(
            Flow.fromGraph(new AttributesFlow(Attributes.name("original-name")))
              .withAttributes(Attributes.name("replaced")) // this actually replaces now
          )(Keep.right)
          .withAttributes(Attributes.name("source-flow"))
          .toMat(Sink.ignore)(Keep.left)
          .withAttributes(Attributes.name("whole-graph"))
          .run()

      // most specific
      attributes.get[Name] should contain(Name("replaced"))

      // least specific
      attributes.getFirst[Name] should contain(Name("whole-graph"))
    }

    "make the attribues on Sink.fromGraph source behave the same as the stage itself" in {
      val attributes =
        Source.maybe.toMat(
          Sink.fromGraph(new AttributesSink(Attributes.name("original-name")))
            .withAttributes(Attributes.name("replaced")) // this actually replaces now
        )(Keep.right)
          .withAttributes(Attributes.name("whole-graph"))
          .run()

      // most specific
      attributes.get[Name] should contain(Name("replaced"))

      // least specific
      attributes.getFirst[Name] should contain(Name("whole-graph"))
    }

    "use the initial attributes for dispatcher" in {
      val dispatcher =
        Source.fromGraph(new ThreadNameSnitchingStage("my-dispatcher"))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "use the least specific dispatcher when specified directly around the graph stage" in {
      val dispatcher =
        Source.fromGraph(
          // directly on stage
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher")
            .addAttributes(ActorAttributes.dispatcher("my-dispatcher")))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "use the least specific dispatcher when specified further out from the stage" in {
      val dispatcher =
        // on the source returned from graph
        Source.fromGraph(
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher"))
          .addAttributes(ActorAttributes.dispatcher("my-dispatcher"))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

  }

}
