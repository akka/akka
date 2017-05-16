/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.Attributes._
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }
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
        .addAttributes(Attributes.name("new-name"))
        .runWith(Sink.head)
        .futureValue

      // most specific
      attributes.get[Name] should contain(Name("original-name"))

      // least specific
      attributes.getFirst[Name] should contain(Name("new-name"))
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

    // just to document the behavior, this creates a nested source with attributes
    // so they are not really replaced on the inner graph
    "wrap the attributes on a graph stage " in {
      val attributes =
        Source.fromGraph(new AttributesSource(Attributes.name("original-name")))
          .withAttributes(Attributes.name("nested-source"))
          .runWith(Sink.head)
          .futureValue

      // most specific
      attributes.get[Name] should contain(Name("original-name"))

      // least specific
      attributes.getFirst[Name] should contain(Name("nested-source"))
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
