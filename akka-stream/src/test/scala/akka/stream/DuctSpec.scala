/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable
import scala.concurrent.duration._
import org.reactivestreams.{ Publisher, Subscriber }
import akka.stream.scaladsl.Duct
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import scala.util.Success
import scala.util.Failure

object DuctSpec {
  class Fruit
  class Apple extends Fruit
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DuctSpec extends AkkaSpec {
  import DuctSpec._

  val materializer = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))

  "A Duct" must {

    "materialize into Publisher/Subscriber" in {
      val duct: Duct[String, String] = Duct[String]
      val (ductIn: Subscriber[String], ductOut: Publisher[String]) = duct.build(materializer)

      val c1 = StreamTestKit.SubscriberProbe[String]()
      ductOut.subscribe(c1)

      val source: Publisher[String] = Flow(List("1", "2", "3")).toPublisher(materializer)
      source.subscribe(ductIn)

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "materialize into Publisher/Subscriber and transformation processor" in {
      val duct: Duct[Int, String] = Duct[Int].map((i: Int) ⇒ i.toString)
      val (ductIn: Subscriber[Int], ductOut: Publisher[String]) = duct.build(materializer)

      val c1 = StreamTestKit.SubscriberProbe[String]()
      ductOut.subscribe(c1)
      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNoMsg(200.millis)

      val source: Publisher[Int] = Flow(List(1, 2, 3)).toPublisher(materializer)
      source.subscribe(ductIn)

      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "materialize into Publisher/Subscriber and multiple transformation processors" in {
      val duct = Duct[Int].map(_.toString).map("elem-" + _)
      val (ductIn, ductOut) = duct.build(materializer)

      val c1 = StreamTestKit.SubscriberProbe[String]()
      ductOut.subscribe(c1)
      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNoMsg(200.millis)

      val source: Publisher[Int] = Flow(List(1, 2, 3)).toPublisher(materializer)
      source.subscribe(ductIn)

      c1.expectNext("elem-1")
      c1.expectNext("elem-2")
      c1.expectNext("elem-3")
      c1.expectComplete
    }

    "subscribe Subscriber" in {
      val duct: Duct[String, String] = Duct[String]
      val c1 = StreamTestKit.SubscriberProbe[String]()
      val c2: Subscriber[String] = duct.produceTo(c1, materializer)
      val source: Publisher[String] = Flow(List("1", "2", "3")).toPublisher(materializer)
      source.subscribe(c2)

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "perform transformation operation" in {
      val duct = Duct[Int].map(i ⇒ { testActor ! i.toString; i.toString })
      val c = duct.consume(materializer)

      val source = Flow(List(1, 2, 3)).toPublisher(materializer)
      source.subscribe(c)

      expectMsg("1")
      expectMsg("2")
      expectMsg("3")
    }

    "perform multiple transformation operations" in {
      val (in, fut) = Duct[Int].map(_.toString).map("elem-" + _).foreach(testActor ! _, materializer)

      val source = Flow(List(1, 2, 3)).toPublisher(materializer)
      source.subscribe(in)

      expectMsg("elem-1")
      expectMsg("elem-2")
      expectMsg("elem-3")
    }

    "perform transformation operation and subscribe Subscriber" in {
      val duct = Duct[Int].map(_.toString)
      val c1 = StreamTestKit.SubscriberProbe[String]()
      val c2: Subscriber[Int] = duct.produceTo(c1, materializer)

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNoMsg(200.millis)

      val source: Publisher[Int] = Flow(List(1, 2, 3)).toPublisher(materializer)
      source.subscribe(c2)

      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "perform multiple transformation operations and subscribe Subscriber" in {
      val duct = Duct[Int].map(_.toString).map("elem-" + _)
      val c1 = StreamTestKit.SubscriberProbe[String]()
      val c2 = duct.produceTo(c1, materializer)

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNoMsg(200.millis)

      val source: Publisher[Int] = Flow(List(1, 2, 3)).toPublisher(materializer)
      source.subscribe(c2)

      c1.expectNext("elem-1")
      c1.expectNext("elem-2")
      c1.expectNext("elem-3")
      c1.expectComplete
    }

    "call onComplete callback when done" in {
      val duct = Duct[Int].map(i ⇒ { testActor ! i.toString; i.toString })
      val c = duct.onComplete({
        case Success(_) ⇒ testActor ! "DONE"
        case Failure(e) ⇒ testActor ! e
      }, materializer)

      val source = Flow(List(1, 2, 3)).toPublisher(materializer)
      source.subscribe(c)

      expectMsg("1")
      expectMsg("2")
      expectMsg("3")
      expectMsg("DONE")
    }

    "be appendable to a Flow" in {
      val c = StreamTestKit.SubscriberProbe[String]()
      val duct = Duct[Int].map(_ + 10).map(_.toString)
      Flow(List(1, 2, 3)).map(_ * 2).append(duct).map((s: String) ⇒ "elem-" + s).produceTo(c, materializer)

      val sub = c.expectSubscription
      sub.request(3)
      c.expectNext("elem-12")
      c.expectNext("elem-14")
      c.expectNext("elem-16")
      c.expectComplete
    }

    "be appendable to a Duct" in {
      val c = StreamTestKit.SubscriberProbe[String]()
      val duct1 = Duct[String].map(Integer.parseInt)
      val ductInSubscriber = Duct[Int]
        .map { i ⇒ (i * 2).toString }
        .append(duct1)
        .map { i ⇒ "elem-" + (i + 10) }
        .produceTo(c, materializer)

      Flow(List(1, 2, 3)).produceTo(ductInSubscriber, materializer)

      val sub = c.expectSubscription
      sub.request(3)
      c.expectNext("elem-12")
      c.expectNext("elem-14")
      c.expectNext("elem-16")
      c.expectComplete
    }

    "be covariant" in {
      val d1: Duct[String, Publisher[Fruit]] = Duct[String].map(_ ⇒ new Apple).splitWhen(_ ⇒ true)
      val d2: Duct[String, (Boolean, Publisher[Fruit])] = Duct[String].map(_ ⇒ new Apple).groupBy(_ ⇒ true)
      val d3: Duct[String, (immutable.Seq[Apple], Publisher[Fruit])] = Duct[String].map(_ ⇒ new Apple).prefixAndTail(1)
      val s1: Subscriber[Fruit] = StreamTestKit.SubscriberProbe[Fruit]()
      val s2: Subscriber[String] = Duct[String].map(_ ⇒ new Apple).produceTo(s1, materializer)
      val t: Tuple2[Subscriber[String], Publisher[Fruit]] = Duct[String].map(_ ⇒ new Apple).build(materializer)
    }

  }

}
