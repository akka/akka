/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.Attributes
import akka.stream.Attributes._
import akka.stream.MaterializationContext
import akka.stream.SinkShape
import akka.stream.testkit._
import scala.concurrent.Future
import scala.concurrent.Promise
import akka.stream.impl.SinkModule
import akka.stream.impl.StreamLayout.Module
import org.scalatest.concurrent.ScalaFutures
import akka.stream.impl.SinkholeSubscriber

object AttributesSpec {

  object AttributesSink {
    def apply(): Sink[Nothing, Future[Attributes]] =
      new Sink(new AttributesSink(Attributes.name("attributesSink"), Sink.shape("attributesSink")))
  }

  final class AttributesSink(val attributes: Attributes, shape: SinkShape[Nothing]) extends SinkModule[Nothing, Future[Attributes]](shape) {
    override def create(context: MaterializationContext) =
      (new SinkholeSubscriber(Promise()), Future.successful(context.effectiveAttributes))

    override protected def newInstance(shape: SinkShape[Nothing]): SinkModule[Nothing, Future[Attributes]] =
      new AttributesSink(attributes, shape)

    override def withAttributes(attr: Attributes): Module =
      new AttributesSink(attr, amendShape(attr))
  }

}

class AttributesSpec extends AkkaSpec with ScalaFutures {
  import AttributesSpec._

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "attributes" must {

    "be overridable on a module basis" in {
      val runnable = Source.empty.toMat(AttributesSink().withAttributes(Attributes.name("new-name")))(Keep.right)
      whenReady(runnable.run()) { attributes ⇒
        attributes.get[Name] should contain(Name("new-name"))
      }
    }

    "keep the outermost attribute as the least specific" in {
      val runnable = Source.empty.toMat(AttributesSink())(Keep.right).withAttributes(Attributes.name("new-name"))
      whenReady(runnable.run()) { attributes ⇒
        attributes.get[Name] should contain(Name("attributesSink"))
      }
    }
  }

}
