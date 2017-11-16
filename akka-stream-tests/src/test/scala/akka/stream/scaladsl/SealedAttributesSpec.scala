/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream._
import akka.stream.Attributes._
import akka.stream.testkit._

import scala.concurrent.Future
import scala.concurrent.Promise
import akka.stream.impl.{ QueueSink, SinkModule, SinkholeSubscriber }
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage._
import org.scalatest.concurrent.ScalaFutures

object SealedAttributesSpec {

  //  object AttributesSink {
  //    def apply(): Sink[Nothing, Future[Attributes]] =
  //      Sink.fromGraph[Nothing, Future[Attributes]](new AttributesSink(Attributes.name("attributesSink"), Sink.shape("attributesSink")))
  //  }
  //
  //  final class AttributesSink(val attributes: Attributes, shape: SinkShape[Nothing]) extends SinkModule[Nothing, Future[Attributes]](shape) {
  //    override def create(context: MaterializationContext) =
  //      (new SinkholeSubscriber(Promise()), Future.successful(context.effectiveAttributes))
  //
  //    override protected def newInstance(shape: SinkShape[Nothing]): SinkModule[Nothing, Future[Attributes]] =
  //      new AttributesSink(attributes, shape)
  //
  //    override def withAttributes(attr: Attributes): SinkModule[Nothing, Future[Attributes]] =
  //      new AttributesSink(attr, amendShape(attr))
  //  }
  //
  //  object AttributesFlow {
  //    def apply(): Flow[Nothing, Attributes, NotUsed] =
  //      Flow.fromGraph[Nothing, Attributes, NotUsed](new GraphStage[FlowShape[Nothing, Attributes]] {
  //        override val shape: FlowShape[Nothing, Attributes] = FlowShape.of(Inlet("in"), Outlet("out"))
  //        override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
  //          override def onPush(): Unit = pull(shape.in)
  //          override def onPull(): Unit = push(shape.out, inheritedAttributes)
  //        }
  //      })
  //  }

  def graphStageSink[T](): Sink[Any, Attributes] =
    Sink.fromGraph(new GraphStageWithMaterializedValue[SinkShape[Any], Attributes] {
      override val shape: SinkShape[Any] = SinkShape.of(Inlet[Any]("in"))
      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
        val logic = new GraphStageLogic(shape) with InHandler {
          override def onPush(): Unit = ()

          setHandler(shape.in, this)
        }

        logic → inheritedAttributes
      }
    })

  def graphStageSink[T, A](get: Attributes ⇒ Option[A], initialAttr: Attributes = Attributes.none): Sink[Any, Option[A]] =
    Sink.fromGraph(new GraphStageWithMaterializedValue[SinkShape[Any], Option[A]] {
      override val shape: SinkShape[Any] = SinkShape.of(Inlet[Any]("in"))
      override def initialAttributes = initialAttr

      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
        val logic = new GraphStageLogic(shape) with InHandler {

          override def onPush(): Unit = ()

          setHandler(shape.in, this)
        }

        logic → get(inheritedAttributes)
      }
    })

  def graphStageSinkShape[T, A](get: Attributes ⇒ Option[A], initialAttr: Attributes = Attributes.none): GraphStageWithMaterializedValue[SinkShape[Any], Option[A]] =
    new GraphStageWithMaterializedValue[SinkShape[Any], Option[A]] {
      override val shape: SinkShape[Any] = SinkShape.of(Inlet[Any]("in"))
      override def initialAttributes = initialAttr

      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
        val logic = new GraphStageLogic(shape) with InHandler {

          println(s" >>> inheritedAttributes = ${inheritedAttributes}")

          val dispatchers = inheritedAttributes.filtered[ActorAttributes.Dispatcher].map(_.dispatcher)
          println(s" >>> dispatchers = ${dispatchers}")

          //          val names = inheritedAttributes.filtered[Name].map(_.n)
          //          println(s" >>> names = ${names.mkString(" nested in: ")}")
          override def onPush(): Unit = ()

          setHandler(shape.in, this)
        }

        logic → get(inheritedAttributes)
      }
    }

}

class SealedAttributesSpec extends StreamSpec(
  s"""
    akka.actor.default-mailbox.mailbox-type = "akka.dispatch.UnboundedMailbox"

    dispatcher-outer = $${akka.actor.default-dispatcher}
    dispatcher-default-in-stage-initial = $${akka.actor.default-dispatcher}
    right-on-sink = $${akka.actor.default-dispatcher}
    completely-outside = $${akka.actor.default-dispatcher}
  """.stripMargin) with ScalaFutures {
  import SealedAttributesSpec._

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  def attributesOf[T](s: RunnableGraph[Attributes]): Attributes = {
    s.run()
  }

  def attributesOf[T](s: RunnableGraph[Option[T]]): Option[T] = {
    s.run()
  }

  "attributes.get" must {
    "return None if not present" in {
      Attributes().get[Name] should ===(None)
    }

    "return it when present, trivial case " in {
      val attrs = Attributes(Name("hello"))
      attrs.get[Name] should ===(Some(Name("hello")))
    }

    "return override, when has 2 values, no seals" in {
      val attrs = Attributes(Name("hello") :: Name("default-name") :: Nil)
      attrs.get[Name] should ===(Some(Name("hello")))
    }

    "return sealed, when has 2 values, default is sealed" in {
      val attrs = Attributes(Name("hello") :: Name("default-name").seal("name") :: Nil)
      attrs.get[Name] should ===(Some(Name("default-name")))
    }

    "return sealed, when has 2 values, default is sealed, yet we unseal-it" in {
      val attrs = Attributes(Name("hello").seal("name") :: Name("default-name").seal("name") :: Nil)
      attrs.get[Name] should ===(Some(Name("hello")))
    }
  }

  "attributes applied to flows" must {

    import ActorAttributes._
    val DispatcherOuter = ActorAttributes.dispatcher("dispatcher-outer")

    "nothing" in {
      val it = Source.empty.toMat(
        graphStageSink()
      )(Keep.right)

      val attrs = attributesOf(it)

      info("attrs: " + attrs)
    }

    "attrs on sink" in {
      val it = Source.empty.toMat(
        graphStageSink().withAttributes(DispatcherOuter)
      )(Keep.right)

      val attrs = attributesOf(it)

      info("attrs: " + attrs)
    }

    "getInside; attrs on sink" in {
      val it = Source.empty.toMat(
        graphStageSink(_.get[Dispatcher]
        ).withAttributes(DispatcherOuter))(Keep.right)

      val maybeAttr = attributesOf(it)

      info("initialAttributes.get[Dispatcher]: " + maybeAttr)
      maybeAttr.get should equal(DispatcherOuter.attributeList.head)
    }
    "getInside; (attrs on sink) set from outer" in {
      val it = Source.empty.toMat(
        graphStageSink(_.get[Dispatcher])
      )(Keep.right).withAttributes(DispatcherOuter)

      val maybeAttr = attributesOf(it)

      info("initialAttributes.get[Dispatcher]: " + maybeAttr)
      maybeAttr.get should equal(DispatcherOuter.attributeList.head)
    }

    //    // current semantics:
    //    "getInside; initial attrs -- nothing outside" in {
    //      val it = Source.empty.toMat(
    //        Sink.fromGraph(graphStageSinkShape(_.get[Name], initialAttr = Attributes.name("initial-inside")))
    //      )(Keep.right)
    //
    //      val maybeAttr = attributesOf(it)
    //
    //      info("initialAttributes.get[Name]: " + maybeAttr)
    //      maybeAttr.get should equal(Attributes.name("initial-inside").attributeList.head)
    //    }
    //
    //    "getInside; initial attrs -- override on stage" in {
    //      val it = Source.empty.toMat(
    //        Sink.fromGraph(
    //          graphStageSinkShape(_.get[Name], initialAttr = Attributes.name("initial-inside"))
    //        ).named("on-sink-override")
    //      )(Keep.right)
    //
    //      val maybeAttr = attributesOf(it)
    //
    //      info("initialAttributes.get[Name]: " + maybeAttr)
    //      maybeAttr.get should equal(Attributes.name("initial-inside").attributeList.head)
    //    }
    //
    //    "getInside; initial attrs -- override on composite ('complete') stage" in {
    //      val it = Source.empty.toMat(
    //        Sink.fromGraph(
    //          graphStageSinkShape(_.get[Name], initialAttr = Attributes.name("initial-inside"))
    //        ).named("on-sink-override")
    //      )(Keep.right).named("on-sink-outside-override")
    //
    //      //  >>> inheritedAttributes = Attributes(List(Name(initial-inside), Name(on-sink-override), Name(on-sink-outside-override), Name(on-sink-outside-override), Dispatcher(akka.test.stream-dispatcher), InputBuffer(2,16), SupervisionStrategy(<function1>)))
    //      // >>> names = initial-inside nested in: on-sink-override nested in: on-sink-outside-override nested in: on-sink-outside-override
    //      val maybeAttr = attributesOf(it)
    //
    //      info("initialAttributes.get[Name]: " + maybeAttr)
    //      maybeAttr.get should equal(Attributes.name("initial-inside").attributeList.head)
    //    }

    // new semantics:

    "xoxo getInside; dispatcher, override, no seal; initial attrs -- override on composite ('complete') stage" in {

      /*
          Here wa have a dispatcher, but we allow it to be overriden
       */

      val it = Source.empty.toMat(
        Sink.fromGraph(
          graphStageSinkShape(_.get[Dispatcher], initialAttr = {
            Attributes(Dispatcher("dispatcher-default-in-stage-initial") :: Name("initInsideGS") :: Nil)
          })
        ).withAttributes(dispatcher("right-on-sink"))
      )(Keep.right)

      val maybeAttr = attributesOf(it)

      info("initialAttributes.get[Dispatcher]: " + maybeAttr)
      maybeAttr.get.dispatcher should equal("right-on-sink")
    }

    "zozo getInside; dispatcher, override, seal; initial attrs -- override on composite ('complete') stage" in {

      /*
          Here wa have a dispatcher, we don't allow overrides
       */

      val it = Source.empty.toMat(
        Sink.fromGraph(
          graphStageSinkShape(_.get[Dispatcher], initialAttr = dispatcher("dispatcher-default-in-stage-initial"))
        ).withAttributes(dispatcher("right-on-sink"))
      )(Keep.right)

      val maybeAttr = attributesOf(it)

      info("initialAttributes.get[Dispatcher]: " + maybeAttr)
      maybeAttr.get.dispatcher should equal("dispatcher-default-in-stage-initial")
    }

    "getInside; initial attrs -- override on composite ('complete') stage" in {
      val it = Source.empty.toMat(
        Sink.fromGraph(
          graphStageSinkShape(_.get[Name], initialAttr = Attributes.name("initial-inside"))
        ).named("on-sink-override")
      )(Keep.right).named("on-sink-outside-override")

      //  >>> inheritedAttributes = Attributes(List(Name(initial-inside), Name(on-sink-override), Name(on-sink-outside-override), Name(on-sink-outside-override), Dispatcher(akka.test.stream-dispatcher), InputBuffer(2,16), SupervisionStrategy(<function1>)))
      // >>> names = initial-inside nested in: on-sink-override nested in: on-sink-outside-override nested in: on-sink-outside-override
      val maybeAttr = attributesOf(it)

      info("initialAttributes.get[Name]: " + maybeAttr)
      maybeAttr.get should equal(Attributes.name("on-sink-outside-override").attributeList.head)
    }

  }
}
