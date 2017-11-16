/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.actor.Deploy
import akka.event.Logging
import akka.stream.Attributes._
import akka.stream._
import akka.stream.stage._
import akka.stream.testkit._
import com.typesafe.config.ConfigFactory

object AttributesSpec {

  class AttributesFlow(_initialAttributes: Attributes = Attributes.none) extends GraphStageWithMaterializedValue[FlowShape[Attributes, Attributes], Attributes] {
    val in = Inlet[Attributes]("in")
    val out = Outlet[Attributes]("out")
    override protected def initialAttributes: Attributes = _initialAttributes
    override val shape = FlowShape.of(in, out)
    def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = (new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        def onPull(): Unit = {
          push(out, inheritedAttributes)
          completeStage()
        }
      })
      setHandler(in, new InHandler {
        def onPush() = pull(in)
      })
    }, inheritedAttributes)
  }

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

  // dispatcher in materializer settings trumps everything
  // therefore we need another materializer with no dispatcher setting to test
  // dispatcher overrides controlled by the attributes
  val materializerNoDispatcher = ActorMaterializer(settings.withDispatcher(Deploy.NoDispatcherGiven))

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
        .addAttributes(Attributes.name("new-name"))
        .runWith(Sink.head)
        .futureValue

      // most outer
      attributes.get[Name] should contain(Name("new-name"))

      // most inner (or defined first)
      attributes.getFirst[Name] should contain(Name("original-name"))
    }

    "replace the attributes directly on a graph stage" in {
      val attributes =
        Source.fromGraph(
          new AttributesSource(Attributes.name("original-name")).withAttributes(Attributes.name("new-name"))
        )
          .runWith(Sink.head)
          .futureValue

      // most outer
      attributes.get[Name] should contain(Name("new-name"))

      // most inner (or defined first)
      attributes.getFirst[Name] should contain(Name("new-name"))
      // FIXME the original got replaced, it was not even seen by the materializer when debugging this
      // might be solved by always wrapping and never replacing attributes
    }

    // just to document the behavior, this creates a nested source with attributes
    // so they are not really replaced on the inner graph
    "wrap the attributes on a graph stage" in {
      val attributes =
        Source.fromGraph(new AttributesSource(Attributes.name("original-name")))
          .withAttributes(Attributes.name("nested-source"))
          .runWith(Sink.head)
          .futureValue

      // most outer
      attributes.get[Name] should contain(Name("nested-source"))

      // most inner (or defined first)
      attributes.getFirst[Name] should contain(Name("original-name"))
    }

    "use the initial attributes for dispatcher" in {
      val dispatcher =
        Source.fromGraph(new ThreadNameSnitchingStage("my-dispatcher"))
          .runWith(Sink.head)(materializerNoDispatcher)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "use the most outer dispatcher when specified directly around the graph stage" in {
      val dispatcher =
        Source.fromGraph(
          // directly on stage
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher")
            .addAttributes(ActorAttributes.dispatcher("my-dispatcher")))
          .runWith(Sink.head)(materializerNoDispatcher)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "use the most outer dispatcher when specified further out from the stage" in {
      val dispatcher =
        // on the source returned from graph
        Source.fromGraph(
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher"))
          .addAttributes(ActorAttributes.dispatcher("my-dispatcher"))
          .runWith(Sink.head)(materializerNoDispatcher)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "use the dispatcher set in materializer even if dispatcher specified in initial attributes" in {
      val dispatcher =
        Source.fromGraph(new ThreadNameSnitchingStage("my-dispatcher"))
          .runWith(Sink.head)(materializerNoDispatcher)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "dispatcher in materializer trumps dispatcher specified directly around the graph stage" in {
      val dispatcher =
        Source.fromGraph(
          // directly on stage
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher")
            .addAttributes(ActorAttributes.dispatcher("my-dispatcher")))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-akka.test.stream-dispatcher")
    }

    "dispatcher in materializer trumps dispatcher specified further out from the stage" in {
      val dispatcher =
        // on the source returned from graph
        Source.fromGraph(
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher"))
          .addAttributes(ActorAttributes.dispatcher("my-dispatcher"))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-akka.test.stream-dispatcher")
    }

    "attributes on atoms must remain after being composed" in {
      val component = Flow.fromGraph(new AttributesFlow())

      val logSilent = component.withAttributes(Attributes.logLevels(onElement = Logging.ErrorLevel))
      val logVerbose = component.withAttributes(Attributes.logLevels(onElement = Logging.DebugLevel))

      val (silent, verbose) = Source.empty[Attributes]
        .viaMat(logSilent)(Keep.right)
        .viaMat(logVerbose)(Keep.both)
        .toMat(Sink.ignore)(Keep.left)
        .run()

      silent.get[LogLevels].map(_.onElement) should contain(Logging.ErrorLevel)
      verbose.get[LogLevels].map(_.onElement) should contain(Logging.DebugLevel)
    }

  }

}
