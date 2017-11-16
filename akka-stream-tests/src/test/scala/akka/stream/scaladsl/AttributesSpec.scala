/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.Optional
import java.util.concurrent.{ CompletableFuture, CompletionStage, TimeUnit }

import akka.Done
import akka.stream.Attributes._
import akka.stream._
import akka.stream.javadsl
import akka.stream.stage._
import akka.stream.testkit._
import com.typesafe.config.ConfigFactory

object AttributesSpec {

  class AttributesSource(_initialAttributes: Attributes = Attributes.none) extends GraphStageWithMaterializedValue[SourceShape[Any], Attributes] {
    val out = Outlet[Any]("out")
    override protected def initialAttributes: Attributes = _initialAttributes
    override val shape = SourceShape.of(out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Attributes) = {
      val logic = new GraphStageLogic(shape) {
        setHandler(out, new OutHandler {
          def onPull(): Unit = {
          }
        })
      }
      (logic, inheritedAttributes)
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

  def whateverAttribute(label: String): Attributes = Attributes(WhateverAttribute(label))
  case class WhateverAttribute(label: String) extends Attribute
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
      attributes.leastSpecific[Name] should ===(Some(Attributes.Name("a")))
    }

    "give access to attribute byt type" in {
      attributes.mostSpecific[Name] should ===(Some(Attributes.Name("b")))
    }

  }

  "attributes on a stage" must {

    "be appended with addAttributes" in {
      val attributes =
        Source.fromGraph(new AttributesSource()
          .addAttributes(Attributes.name("new-name"))
          .addAttributes(Attributes.name("re-added")) // adding twice at same level replaces
          .addAttributes(whateverAttribute("other-thing"))
        )
          .toMat(Sink.head)(Keep.left)
          .run()

      attributes.mostSpecific[Name] should contain(Name("re-added"))
      attributes.mostSpecific[WhateverAttribute] should contain(WhateverAttribute("other-thing"))
    }

    "be replaced withAttributes" in {
      val attributes =
        Source.fromGraph(new AttributesSource()
          .withAttributes(Attributes.name("new-name") and whateverAttribute("other-thing"))
          .withAttributes(Attributes.name("re-added")) // we loose all previous attributes for same level
        )
          .toMat(Sink.head)(Keep.left)
          .run()

      attributes.mostSpecific[Name] should contain(Name("re-added"))
      attributes.mostSpecific[WhateverAttribute] shouldBe empty
    }

    "be overridable on a module basis" in {
      val attributes =
        Source.fromGraph(new AttributesSource().withAttributes(Attributes.name("new-name")))
          .toMat(Sink.head)(Keep.left)
          .run()

      attributes.mostSpecific[Name] should contain(Name("new-name"))
    }

    "keep the outermost attribute as the least specific" in {
      val attributes = Source.fromGraph(new AttributesSource(Attributes.name("original-name")))
        .map(identity)
        .addAttributes(Attributes.name("whole-graph"))
        .toMat(Sink.head)(Keep.left)
        .run()

      // most specific
      attributes.mostSpecific[Name] should contain(Name("original-name"))

      // least specific
      attributes.leastSpecific[Name] should contain(Name("whole-graph"))
    }

    "replace the attributes directly on a graph stage" in {
      val attributes =
        Source.fromGraph(
          new AttributesSource(Attributes.name("original-name"))
            .withAttributes(Attributes.name("new-name")))
          .toMat(Sink.head)(Keep.left)
          .run()

      // most specific
      attributes.mostSpecific[Name] should contain(Name("new-name"))

      // least specific
      attributes.leastSpecific[Name] should contain(Name("new-name"))
    }

    "make the attributes on Source.fromGraph source behave the same as the stage itself" in {
      val attributes =
        Source.fromGraph(new AttributesSource(Attributes.name("original-name")))
          .withAttributes(Attributes.name("replaced")) // this actually replaces now
          .toMat(Sink.head)(Keep.left).withAttributes(Attributes.name("whole-graph"))
          .run()

      // most specific
      attributes.mostSpecific[Name] should contain(Name("replaced"))

      // least specific
      attributes.leastSpecific[Name] should contain(Name("whole-graph"))
    }

    "make the attributes on Flow.fromGraph source behave the same as the stage itself" in {
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
      attributes.mostSpecific[Name] should contain(Name("replaced"))

      // least specific
      attributes.leastSpecific[Name] should contain(Name("whole-graph"))
    }

    "make the attributes on Sink.fromGraph source behave the same as the stage itself" in {
      val attributes =
        Source.maybe.toMat(
          Sink.fromGraph(new AttributesSink(Attributes.name("original-name")))
            .withAttributes(Attributes.name("replaced")) // this actually replaces now
        )(Keep.right)
          .withAttributes(Attributes.name("whole-graph"))
          .run()

      // most specific
      attributes.mostSpecific[Name] should contain(Name("replaced"))

      // least specific
      attributes.leastSpecific[Name] should contain(Name("whole-graph"))
    }

    "use the initial attributes for dispatcher" in {
      val dispatcher =
        Source.fromGraph(new ThreadNameSnitchingStage("my-dispatcher"))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "use the most specific dispatcher when specified directly around the graph stage" in {
      val dispatcher =
        Source.fromGraph(
          // directly on stage
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher")
            .addAttributes(ActorAttributes.dispatcher("my-dispatcher")))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "use the most specific dispatcher when defined on a surrounding composed graph" in {
      val dispatcher =
        Source.fromGraph(
          // on the composed graph
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher"))
          .map(identity)
          // this is now for source -> flow
          .addAttributes(ActorAttributes.dispatcher("my-dispatcher"))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-akka.stream.default-blocking-io-dispatcher")
    }

    "not change dispatcher from one defined on a surrounding graph" in {
      val dispatcher =
        Source.fromGraph(
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher"))
          // this already introduces an async boundary here
          .map(identity)
          // this is now just for map since there already is one inbetween stage and map
          .async // potential sugar .async("my-dispatcher")
          .addAttributes(ActorAttributes.dispatcher("my-dispatcher"))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-akka.stream.default-blocking-io-dispatcher")
    }

    "change dispatcher when defined directly on top of the async boundary" in {
      val dispatcher =
        Source.fromGraph(
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher"))
          .async
          .withAttributes(ActorAttributes.dispatcher("my-dispatcher"))
          .runWith(Sink.head)
          .futureValue

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

  }

  "attributes in the javadsl" must {

    "not change dispatcher from one defined on a surrounding graph" in {
      val dispatcherF =
        javadsl.Source.fromGraph(
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher"))
          // this already introduces an async boundary here
          .detach
          // this is now just for map since there already is one inbetween stage and map
          .async
          .addAttributes(ActorAttributes.dispatcher("my-dispatcher"))
          .runWith(javadsl.Sink.head(), materializer)

      val dispatcher = dispatcherF.toCompletableFuture.get(remainingOrDefault.toMillis, TimeUnit.MILLISECONDS)

      dispatcher should startWith("AttributesSpec-akka.stream.default-blocking-io-dispatcher")
    }

    "change dispatcher when defined directly on top of the async boundary" in {
      val dispatcherF =
        javadsl.Source.fromGraph(
          new ThreadNameSnitchingStage("akka.stream.default-blocking-io-dispatcher"))
          .async
          .withAttributes(ActorAttributes.dispatcher("my-dispatcher"))
          .runWith(javadsl.Sink.head(), materializer)

      val dispatcher = dispatcherF.toCompletableFuture.get(remainingOrDefault.toMillis, TimeUnit.MILLISECONDS)

      dispatcher should startWith("AttributesSpec-my-dispatcher")
    }

    "make the attributes on Source.fromGraph source behave the same as the stage itself" in {
      val attributes: Attributes =
        javadsl.Source.fromGraph(new AttributesSource(Attributes.name("original-name")))
          .withAttributes(Attributes.name("replaced")) // this actually replaces now
          .toMat(javadsl.Sink.ignore(), javadsl.Keep.left[Attributes, CompletionStage[Done]])
          .withAttributes(Attributes.name("whole-graph"))
          .run(materializer)

      // most specific
      attributes.mostSpecific[Name] should contain(Name("replaced"))

      // least specific
      attributes.leastSpecific[Name] should contain(Name("whole-graph"))
    }

    "make the attributes on Flow.fromGraph source behave the same as the stage itself" in {
      val attributes: Attributes =
        javadsl.Source.maybe
          .viaMat(
            javadsl.Flow.fromGraph(new AttributesFlow(Attributes.name("original-name")))
              .withAttributes(Attributes.name("replaced")) // this actually replaces now
              , javadsl.Keep.right[CompletableFuture[Optional[Any]], Attributes])
          .withAttributes(Attributes.name("source-flow"))
          .toMat(javadsl.Sink.ignore(), javadsl.Keep.left[Attributes, CompletionStage[Done]])
          .withAttributes(Attributes.name("whole-graph"))
          .run(materializer)

      // most specific
      attributes.mostSpecific[Name] should contain(Name("replaced"))

      // least specific
      attributes.leastSpecific[Name] should contain(Name("whole-graph"))
    }

    "make the attributes on Sink.fromGraph source behave the same as the stage itself" in {
      val attributes: Attributes =
        javadsl.Source.maybe[Any].toMat(
          javadsl.Sink.fromGraph(new AttributesSink(Attributes.name("original-name")))
            .withAttributes(Attributes.name("replaced")) // this actually replaces now
            , javadsl.Keep.right[CompletableFuture[Optional[Any]], Attributes])
          .withAttributes(Attributes.name("whole-graph"))
          .run(materializer)

      // most specific
      attributes.mostSpecific[Name] should contain(Name("replaced"))

      // least specific
      attributes.leastSpecific[Name] should contain(Name("whole-graph"))
    }

  }

}
