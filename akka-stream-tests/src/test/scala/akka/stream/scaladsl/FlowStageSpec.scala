/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.testkit.{ EventFilter, TestProbe }
import com.typesafe.config.ConfigFactory
import akka.stream.stage._

class FlowStageSpec extends AkkaSpec(ConfigFactory.parseString("akka.actor.debug.receive=off\nakka.loglevel=INFO")) {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  "A Flow with transform operations" must {
    "produce one-to-one transformation as expected" in {
      val p = Source(List(1, 2, 3)).runWith(Sink.publisher)
      val p2 = Source(p).
        transform(() ⇒ new PushStage[Int, Int] {
          var tot = 0
          override def onPush(elem: Int, ctx: Context[Int]) = {
            tot += elem
            ctx.push(tot)
          }
        }).
        runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(1)
      subscriber.expectNext(1)
      subscriber.expectNoMsg(200.millis)
      subscription.request(2)
      subscriber.expectNext(3)
      subscriber.expectNext(6)
      subscriber.expectComplete()
    }

    "produce one-to-several transformation as expected" in {
      val p = Source(List(1, 2, 3)).runWith(Sink.publisher)
      val p2 = Source(p).
        transform(() ⇒ new StatefulStage[Int, Int] {
          var tot = 0

          lazy val waitForNext = new State {
            override def onPush(elem: Int, ctx: Context[Int]) = {
              tot += elem
              emit(Iterator.fill(elem)(tot), ctx)
            }
          }

          override def initial = waitForNext

          override def onUpstreamFinish(ctx: Context[Int]): TerminationDirective = {
            if (current eq waitForNext) ctx.finish()
            else ctx.absorbTermination()
          }

        }).
        runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(4)
      subscriber.expectNext(1)
      subscriber.expectNext(3)
      subscriber.expectNext(3)
      subscriber.expectNext(6)
      subscriber.expectNoMsg(200.millis)
      subscription.request(100)
      subscriber.expectNext(6)
      subscriber.expectNext(6)
      subscriber.expectComplete()
    }

    "produce dropping transformation as expected" in {
      val p = Source(List(1, 2, 3, 4)).runWith(Sink.publisher)
      val p2 = Source(p).
        transform(() ⇒ new PushStage[Int, Int] {
          var tot = 0
          override def onPush(elem: Int, ctx: Context[Int]) = {
            tot += elem
            if (elem % 2 == 0)
              ctx.pull()
            else
              ctx.push(tot)
          }
        }).
        runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(1)
      subscriber.expectNext(1)
      subscriber.expectNoMsg(200.millis)
      subscription.request(1)
      subscriber.expectNext(6)
      subscription.request(1)
      subscriber.expectComplete()
    }

    "produce multi-step transformation as expected" in {
      val p = Source(List("a", "bc", "def")).runWith(Sink.publisher)
      val p2 = Source(p).
        transform(() ⇒ new PushStage[String, Int] {
          var concat = ""
          override def onPush(elem: String, ctx: Context[Int]) = {
            concat += elem
            ctx.push(concat.length)
          }
        }).
        transform(() ⇒ new PushStage[Int, Int] {
          var tot = 0
          override def onPush(length: Int, ctx: Context[Int]) = {
            tot += length
            ctx.push(tot)
          }
        }).
        runWith(Sink.fanoutPublisher(2, 2))
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c1)
      val sub1 = c1.expectSubscription()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      sub2.request(2)
      c1.expectNext(1)
      c2.expectNext(1)
      c2.expectNext(4)
      c1.expectNoMsg(200.millis)
      sub1.request(2)
      sub2.request(2)
      c1.expectNext(4)
      c1.expectNext(10)
      c2.expectNext(10)
      c1.expectComplete()
      c2.expectComplete()
    }

    "support emit onUpstreamFinish" in {
      val p = Source(List("a")).runWith(Sink.publisher)
      val p2 = Source(p).
        transform(() ⇒ new StatefulStage[String, String] {
          var s = ""
          override def initial = new State {
            override def onPush(element: String, ctx: Context[String]) = {
              s += element
              ctx.pull()
            }
          }
          override def onUpstreamFinish(ctx: Context[String]) =
            terminationEmit(Iterator.single(s + "B"), ctx)
        }).
        runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[String]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(1)
      c.expectNext("aB")
      c.expectComplete()
    }

    "allow early finish" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val p2 = Source(p).
        transform(() ⇒ new PushStage[Int, Int] {
          var s = ""
          override def onPush(element: Int, ctx: Context[Int]) = {
            s += element
            if (s == "1")
              ctx.pushAndFinish(element)
            else
              ctx.push(element)
          }
        }).
        runWith(Sink.publisher)
      val proc = p.expectSubscription
      val c = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectComplete()
      proc.expectCancellation()
    }

    "report error when exception is thrown" in {
      val p = Source(List(1, 2, 3)).runWith(Sink.publisher)
      val p2 = Source(p).
        transform(() ⇒ new StatefulStage[Int, Int] {
          override def initial = new State {
            override def onPush(elem: Int, ctx: Context[Int]) = {
              if (elem == 2) {
                throw new IllegalArgumentException("two not allowed")
              } else {
                emit(Iterator(elem, elem), ctx)
              }
            }
          }
        }).
        runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        subscription.request(100)
        subscriber.expectNext(1)
        subscriber.expectNext(1)
        subscriber.expectError().getMessage should be("two not allowed")
        subscriber.expectNoMsg(200.millis)
      }
    }

    "support emit of final elements when onUpstreamFailure" in {
      val p = Source(List(1, 2, 3)).runWith(Sink.publisher)
      val p2 = Source(p).
        map(elem ⇒ if (elem == 2) throw new IllegalArgumentException("two not allowed") else elem).
        transform(() ⇒ new StatefulStage[Int, Int] {
          override def initial = new State {
            override def onPush(elem: Int, ctx: Context[Int]) = ctx.push(elem)
          }

          override def onUpstreamFailure(cause: Throwable, ctx: Context[Int]) = {
            terminationEmit(Iterator(100, 101), ctx)
          }
        }).
        filter(elem ⇒ elem != 1). // it's undefined if element 1 got through before the error or not
        runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        subscription.request(100)
        subscriber.expectNext(100)
        subscriber.expectNext(101)
        subscriber.expectComplete()
        subscriber.expectNoMsg(200.millis)
      }
    }

    "support cancel as expected" in {
      val p = Source(List(1, 2, 3)).runWith(Sink.publisher)
      val p2 = Source(p).
        transform(() ⇒ new StatefulStage[Int, Int] {
          override def initial = new State {
            override def onPush(elem: Int, ctx: Context[Int]) =
              emit(Iterator(elem, elem), ctx)
          }
        }).
        runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(2)
      subscriber.expectNext(1)
      subscription.cancel()
      subscriber.expectNext(1)
      subscriber.expectNoMsg(500.millis)
      subscription.request(2)
      subscriber.expectNoMsg(200.millis)
    }

    "support producing elements from empty inputs" in {
      val p = Source(List.empty[Int]).runWith(Sink.publisher)
      val p2 = Source(p).
        transform(() ⇒ new StatefulStage[Int, Int] {
          override def initial = new State {
            override def onPush(elem: Int, ctx: Context[Int]) = ctx.pull()
          }
          override def onUpstreamFinish(ctx: Context[Int]) =
            terminationEmit(Iterator(1, 2, 3), ctx)
        }).
        runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(4)
      subscriber.expectNext(1)
      subscriber.expectNext(2)
      subscriber.expectNext(3)
      subscriber.expectComplete()

    }

    "support converting onComplete into onError" in {
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Source(List(5, 1, 2, 3)).transform(() ⇒ new PushStage[Int, Int] {
        var expectedNumberOfElements: Option[Int] = None
        var count = 0
        override def onPush(elem: Int, ctx: Context[Int]) =
          if (expectedNumberOfElements.isEmpty) {
            expectedNumberOfElements = Some(elem)
            ctx.pull()
          } else {
            count += 1
            ctx.push(elem)
          }

        override def onUpstreamFinish(ctx: Context[Int]) =
          expectedNumberOfElements match {
            case Some(expected) if count != expected ⇒
              throw new RuntimeException(s"Expected $expected, got $count") with NoStackTrace
            case _ ⇒ ctx.finish()
          }
      }).to(Sink(subscriber)).run()

      val subscription = subscriber.expectSubscription()
      subscription.request(10)

      subscriber.expectNext(1)
      subscriber.expectNext(2)
      subscriber.expectNext(3)
      subscriber.expectError().getMessage should be("Expected 5, got 3")
    }

    "be safe to reuse" in {
      val flow = Source(1 to 3).transform(() ⇒
        new PushStage[Int, Int] {
          var count = 0

          override def onPush(elem: Int, ctx: Context[Int]) = {
            count += 1
            ctx.push(count)
          }
        })

      val s1 = StreamTestKit.SubscriberProbe[Int]()
      flow.to(Sink(s1)).run()
      s1.expectSubscription().request(3)
      s1.expectNext(1, 2, 3)
      s1.expectComplete()

      val s2 = StreamTestKit.SubscriberProbe[Int]()
      flow.to(Sink(s2)).run()
      s2.expectSubscription().request(3)
      s2.expectNext(1, 2, 3)
      s2.expectComplete()
    }
  }

}
