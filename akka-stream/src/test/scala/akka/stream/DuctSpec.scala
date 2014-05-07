/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import org.reactivestreams.api.Consumer
import org.reactivestreams.api.Producer
import akka.stream.scaladsl.Duct
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import scala.util.Success
import scala.util.Failure

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DuctSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings())

  "A Duct" must {

    "materialize into Producer/Consumer" in {
      val duct: Duct[String, String] = Duct[String]
      val (ductIn: Consumer[String], ductOut: Producer[String]) = duct.build(materializer)

      val c1 = StreamTestKit.consumerProbe[String]
      ductOut.produceTo(c1)

      val source: Producer[String] = Flow(List("1", "2", "3")).toProducer(materializer)
      source.produceTo(ductIn)

      val sub1 = c1.expectSubscription
      sub1.requestMore(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "materialize into Producer/Consumer and transformation processor" in {
      val duct: Duct[Int, String] = Duct[Int].map((i: Int) ⇒ i.toString)
      val (ductIn: Consumer[Int], ductOut: Producer[String]) = duct.build(materializer)

      val c1 = StreamTestKit.consumerProbe[String]
      ductOut.produceTo(c1)
      val sub1 = c1.expectSubscription
      sub1.requestMore(3)
      c1.expectNoMsg(200.millis)

      val source: Producer[Int] = Flow(List(1, 2, 3)).toProducer(materializer)
      source.produceTo(ductIn)

      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "materialize into Producer/Consumer and multiple transformation processors" in {
      val duct = Duct[Int].map(_.toString).map("elem-" + _)
      val (ductIn, ductOut) = duct.build(materializer)

      val c1 = StreamTestKit.consumerProbe[String]
      ductOut.produceTo(c1)
      val sub1 = c1.expectSubscription
      sub1.requestMore(3)
      c1.expectNoMsg(200.millis)

      val source: Producer[Int] = Flow(List(1, 2, 3)).toProducer(materializer)
      source.produceTo(ductIn)

      c1.expectNext("elem-1")
      c1.expectNext("elem-2")
      c1.expectNext("elem-3")
      c1.expectComplete
    }

    "produceTo Consumer" in {
      val duct: Duct[String, String] = Duct[String]
      val c1 = StreamTestKit.consumerProbe[String]
      val c2: Consumer[String] = duct.produceTo(materializer, c1)
      val source: Producer[String] = Flow(List("1", "2", "3")).toProducer(materializer)
      source.produceTo(c2)

      val sub1 = c1.expectSubscription
      sub1.requestMore(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "perform transformation operation" in {
      val duct = Duct[Int].map(i ⇒ { testActor ! i.toString; i.toString })
      val c = duct.consume(materializer)

      val source = Flow(List(1, 2, 3)).toProducer(materializer)
      source.produceTo(c)

      expectMsg("1")
      expectMsg("2")
      expectMsg("3")
    }

    "perform multiple transformation operations" in {
      val duct = Duct[Int].map(_.toString).map("elem-" + _).foreach(testActor ! _)
      val c = duct.consume(materializer)

      val source = Flow(List(1, 2, 3)).toProducer(materializer)
      source.produceTo(c)

      expectMsg("elem-1")
      expectMsg("elem-2")
      expectMsg("elem-3")
    }

    "perform transformation operation and produceTo Consumer" in {
      val duct = Duct[Int].map(_.toString)
      val c1 = StreamTestKit.consumerProbe[String]
      val c2: Consumer[Int] = duct.produceTo(materializer, c1)

      val sub1 = c1.expectSubscription
      sub1.requestMore(3)
      c1.expectNoMsg(200.millis)

      val source: Producer[Int] = Flow(List(1, 2, 3)).toProducer(materializer)
      source.produceTo(c2)

      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "perform multiple transformation operations and produceTo Consumer" in {
      val duct = Duct[Int].map(_.toString).map("elem-" + _)
      val c1 = StreamTestKit.consumerProbe[String]
      val c2 = duct.produceTo(materializer, c1)

      val sub1 = c1.expectSubscription
      sub1.requestMore(3)
      c1.expectNoMsg(200.millis)

      val source: Producer[Int] = Flow(List(1, 2, 3)).toProducer(materializer)
      source.produceTo(c2)

      c1.expectNext("elem-1")
      c1.expectNext("elem-2")
      c1.expectNext("elem-3")
      c1.expectComplete
    }

    "call onComplete callback when done" in {
      val duct = Duct[Int].map(i ⇒ { testActor ! i.toString; i.toString })
      val c = duct.onComplete(materializer) {
        case Success(_) ⇒ testActor ! "DONE"
        case Failure(e) ⇒ testActor ! e
      }

      val source = Flow(List(1, 2, 3)).toProducer(materializer)
      source.produceTo(c)

      expectMsg("1")
      expectMsg("2")
      expectMsg("3")
      expectMsg("DONE")
    }

  }

}
