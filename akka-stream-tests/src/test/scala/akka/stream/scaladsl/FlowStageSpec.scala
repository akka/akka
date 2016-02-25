/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.stage._
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.{ EventFilter, TestProbe }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.AkkaSpec

class FlowStageSpec extends AkkaSpec(ConfigFactory.parseString("akka.actor.debug.receive=off\nakka.loglevel=INFO")) {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  "A Flow with transform operations" must {
    "produce one-to-one transformation as expected" in assertAllStagesStopped {
      val p = Source(List(1, 2, 3)).runWith(Sink.asPublisher(false))
      val p2 = Source.fromPublisher(p).
        transform(() ⇒ new PushStage[Int, Int] {
          var tot = 0
          override def onPush(elem: Int, ctx: Context[Int]) = {
            tot += elem
            ctx.push(tot)
          }
        }).
        runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[Int]()
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

    "produce one-to-several transformation as expected" in assertAllStagesStopped {
      val p = Source(List(1, 2, 3)).runWith(Sink.asPublisher(false))
      val p2 = Source.fromPublisher(p).
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
        runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[Int]()
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

    "produce one-to-several transformation with state change" in {
      val p =
        Source(List(3, 2, 1, 0, 1, 12)).
          transform(() ⇒ new StatefulStage[Int, Int] {
            // a transformer that
            //  - for the first element, returns n times 42
            //  - echos the remaining elements (can be reset to the duplication state by getting `0`)

            override def initial = inflate
            lazy val inflate: State = new State {
              override def onPush(elem: Int, ctx: Context[Int]) = {
                emit(Iterator.fill(elem)(42), ctx, echo)
              }
            }
            lazy val echo: State = new State {
              def onPush(elem: Int, ctx: Context[Int]): SyncDirective =
                if (elem == 0) {
                  become(inflate)
                  ctx.pull()
                } else ctx.push(elem)
            }
          }).runWith(Sink.asPublisher(false))

      val subscriber = TestSubscriber.manualProbe[Int]()
      p.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(50)

      // inflating: 3 times 42
      subscriber.expectNext(42)
      subscriber.expectNext(42)
      subscriber.expectNext(42)

      // echoing
      subscriber.expectNext(2)
      subscriber.expectNext(1)

      // reset
      // inflating: 1 times 42
      subscriber.expectNext(42)

      // echoing
      subscriber.expectNext(12)
      subscriber.expectComplete()
    }

    "produce dropping transformation as expected" in {
      val p = Source(List(1, 2, 3, 4)).runWith(Sink.asPublisher(false))
      val p2 = Source.fromPublisher(p).
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
        runWith(Sink.asPublisher(false))
      val subscriber = TestSubscriber.manualProbe[Int]()
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
      val p = Source(List("a", "bc", "def")).runWith(Sink.asPublisher(false))
      val p2 = Source.fromPublisher(p).
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
        runWith(Sink.asPublisher(true))
      val c1 = TestSubscriber.manualProbe[Int]()
      p2.subscribe(c1)
      val sub1 = c1.expectSubscription()
      val c2 = TestSubscriber.manualProbe[Int]()
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

    "support emit onUpstreamFinish" in assertAllStagesStopped {
      val p = Source(List("a")).runWith(Sink.asPublisher(false))
      val p2 = Source.fromPublisher(p).
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
        runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[String]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(1)
      c.expectNext("aB")
      c.expectComplete()
    }

    "allow early finish" in assertAllStagesStopped {
      val (p1, p2) = TestSource.probe[Int].
        transform(() ⇒ new PushStage[Int, Int] {
          var s = ""
          override def onPush(element: Int, ctx: Context[Int]) = {
            s += element
            if (s == "1")
              ctx.pushAndFinish(element)
            else
              ctx.push(element)
          }
        })
        .toMat(TestSink.probe[Int])(Keep.both).run
      p2.request(10)
      p1.sendNext(1)
        .sendNext(2)
      p2.expectNext(1)
        .expectComplete()
      p1.expectCancellation()
    }

    "report error when exception is thrown" in assertAllStagesStopped {
      val p = Source(List(1, 2, 3)).runWith(Sink.asPublisher(false))
      val p2 = Source.fromPublisher(p).
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
        runWith(TestSink.probe[Int])
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        p2.request(100)
          .expectNext(1)
          .expectNext(1)
          .expectError().getMessage should be("two not allowed")
        p2.expectNoMsg(200.millis)
      }
    }

    "support emit of final elements when onUpstreamFailure" in assertAllStagesStopped {
      val p = Source(List(1, 2, 3)).runWith(Sink.asPublisher(false))
      val p2 = Source.fromPublisher(p).
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
        runWith(TestSink.probe[Int])
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        p2.request(100)
          .expectNext(100)
          .expectNext(101)
          .expectComplete()
          .expectNoMsg(200.millis)
      }
    }

    "support cancel as expected" in assertAllStagesStopped {
      val p = Source(1 to 100).runWith(Sink.asPublisher(false))
      val received = Source.fromPublisher(p).
        transform(() ⇒ new StatefulStage[Int, Int] {
          override def initial = new State {
            override def onPush(elem: Int, ctx: Context[Int]) =
              emit(Iterator(elem, elem), ctx)
          }
        })
        .runWith(TestSink.probe[Int])
        .request(1000)
        .expectNext(1)
        .cancel()
        .receiveWithin(1.second)
      received.size should be < 200
      received.foldLeft((true, 1)) {
        case ((flag, last), next) ⇒ (flag && (last == next || last == next - 1), next)
      }._1 should be(true)
    }

    "support producing elements from empty inputs" in assertAllStagesStopped {
      val p = Source(List.empty[Int]).runWith(Sink.asPublisher(false))
      Source.fromPublisher(p).
        transform(() ⇒ new StatefulStage[Int, Int] {
          override def initial = new State {
            override def onPush(elem: Int, ctx: Context[Int]) = ctx.pull()
          }
          override def onUpstreamFinish(ctx: Context[Int]) =
            terminationEmit(Iterator(1, 2, 3), ctx)
        })
        .runWith(TestSink.probe[Int])
        .request(4)
        .expectNext(1)
        .expectNext(2)
        .expectNext(3)
        .expectComplete()

    }

    "support converting onComplete into onError" in {
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
      }).runWith(TestSink.probe[Int])
        .request(10)
        .expectNext(1)
        .expectNext(2)
        .expectNext(3)
        .expectError().getMessage should be("Expected 5, got 3")
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

      flow.runWith(TestSink.probe[Int])
        .request(3)
        .expectNext(1, 2, 3)
        .expectComplete()

      flow.runWith(TestSink.probe[Int])
        .request(3)
        .expectNext(1, 2, 3)
        .expectComplete()
    }

    "handle early cancelation" in assertAllStagesStopped {
      val onDownstreamFinishProbe = TestProbe()
      val down = TestSubscriber.manualProbe[Int]()
      val s = Source.asSubscriber[Int].
        transform(() ⇒ new PushStage[Int, Int] {
          override def onPush(elem: Int, ctx: Context[Int]) =
            ctx.push(elem)
          override def onDownstreamFinish(ctx: Context[Int]): TerminationDirective = {
            onDownstreamFinishProbe.ref ! "onDownstreamFinish"
            ctx.finish()
          }
        }).
        to(Sink.fromSubscriber(down)).run()

      val downstream = down.expectSubscription()
      downstream.cancel()
      onDownstreamFinishProbe.expectMsg("onDownstreamFinish")

      val up = TestPublisher.manualProbe[Int]()
      up.subscribe(s)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

    "not trigger onUpstreamFinished after pushAndFinish" in assertAllStagesStopped {
      val in = TestPublisher.manualProbe[Int]()
      val flow =
        Source.fromPublisher(in)
          .transform(() ⇒ new StatefulStage[Int, Int] {

            def initial: StageState[Int, Int] = new State {
              override def onPush(element: Int, ctx: Context[Int]) =
                ctx.pushAndFinish(element)
            }
            override def onUpstreamFinish(ctx: Context[Int]): TerminationDirective =
              terminationEmit(Iterator(42), ctx)
          })
          .runWith(Sink.asPublisher(false))

      val inSub = in.expectSubscription()

      val out = TestSubscriber.manualProbe[Int]()
      flow.subscribe(out)
      val outSub = out.expectSubscription()

      inSub.sendNext(23)
      inSub.sendComplete()

      outSub.request(1) // it depends on this line, i.e. generating backpressure between buffer and stage execution

      out.expectNext(23)
      out.expectComplete()
    }

    "chain elements to currently emitting on upstream finish" in assertAllStagesStopped {
      Source.single("hi")
        .transform(() ⇒ new StatefulStage[String, String] {
          override def initial = new State {
            override def onPush(elem: String, ctx: Context[String]) =
              emit(Iterator(elem + "1", elem + "2"), ctx)
          }
          override def onUpstreamFinish(ctx: Context[String]) = {
            terminationEmit(Iterator("byebye"), ctx)
          }
        })
        .runWith(TestSink.probe[String])
        .request(1)
        .expectNext("hi1")
        .request(2)
        .expectNext("hi2")
        .expectNext("byebye")
        .expectComplete()
    }
  }

}
