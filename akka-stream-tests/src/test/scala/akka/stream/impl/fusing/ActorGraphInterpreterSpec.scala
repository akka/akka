/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import java.util.concurrent.CountDownLatch

import akka.stream._
import akka.stream.impl.ReactiveStreamsCompliance.SpecViolation
import akka.stream.scaladsl._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.testkit.Utils._
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.EventFilter

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.testkit.AkkaSpec
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

class ActorGraphInterpreterSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()

  "ActorGraphInterpreter" must {

    "be able to interpret a simple identity graph stage" in assertAllStagesStopped {
      val identity = GraphStages.identity[Int]

      Await.result(
        Source(1 to 100).via(identity).grouped(200).runWith(Sink.head),
        3.seconds) should ===(1 to 100)

    }

    "be able to reuse a simple identity graph stage" in assertAllStagesStopped {
      val identity = GraphStages.identity[Int]

      Await.result(
        Source(1 to 100)
          .via(identity)
          .via(identity)
          .via(identity)
          .grouped(200)
          .runWith(Sink.head),
        3.seconds) should ===(1 to 100)
    }

    "be able to interpret a simple bidi stage" in assertAllStagesStopped {
      val identityBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in1, new InHandler {
            override def onPush(): Unit = push(out1, grab(in1))
            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = push(out2, grab(in2))
            override def onUpstreamFinish(): Unit = complete(out2)
          })

          setHandler(out1, new OutHandler {
            override def onPull(): Unit = pull(in1)
            override def onDownstreamFinish(): Unit = cancel(in1)
          })

          setHandler(out2, new OutHandler {
            override def onPull(): Unit = pull(in2)
            override def onDownstreamFinish(): Unit = cancel(in2)
          })
        }

        override def toString = "IdentityBidi"
      }

      val identity = BidiFlow.fromGraph(identityBidi).join(Flow[Int].map { x ⇒ x })

      Await.result(
        Source(1 to 10).via(identity).grouped(100).runWith(Sink.head),
        3.seconds) should ===(1 to 10)

    }

    "be able to interpret and reuse a simple bidi stage" in assertAllStagesStopped {
      val identityBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in1, new InHandler {
            override def onPush(): Unit = push(out1, grab(in1))

            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = push(out2, grab(in2))

            override def onUpstreamFinish(): Unit = complete(out2)
          })

          setHandler(out1, new OutHandler {
            override def onPull(): Unit = pull(in1)

            override def onDownstreamFinish(): Unit = cancel(in1)
          })

          setHandler(out2, new OutHandler {
            override def onPull(): Unit = pull(in2)

            override def onDownstreamFinish(): Unit = cancel(in2)
          })
        }

        override def toString = "IdentityBidi"
      }

      val identityBidiF = BidiFlow.fromGraph(identityBidi)
      val identity = (identityBidiF atop identityBidiF atop identityBidiF).join(Flow[Int].map { x ⇒ x })

      Await.result(
        Source(1 to 10).via(identity).grouped(100).runWith(Sink.head),
        3.seconds) should ===(1 to 10)

    }

    "be able to interpret and resuse a simple bidi stage" in assertAllStagesStopped {
      val identityBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in1, new InHandler {
            override def onPush(): Unit = push(out1, grab(in1))

            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = push(out2, grab(in2))

            override def onUpstreamFinish(): Unit = complete(out2)
          })

          setHandler(out1, new OutHandler {
            override def onPull(): Unit = pull(in1)

            override def onDownstreamFinish(): Unit = cancel(in1)
          })

          setHandler(out2, new OutHandler {
            override def onPull(): Unit = pull(in2)

            override def onDownstreamFinish(): Unit = cancel(in2)
          })
        }

        override def toString = "IdentityBidi"
      }

      val identityBidiF = BidiFlow.fromGraph(identityBidi)
      val identity = (identityBidiF atop identityBidiF atop identityBidiF).join(Flow[Int].map { x ⇒ x })

      Await.result(
        Source(1 to 10).via(identity).grouped(100).runWith(Sink.head),
        3.seconds) should ===(1 to 10)

    }

    "be able to interpret a rotated identity bidi stage" in assertAllStagesStopped {
      // This is a "rotated" identity BidiStage, as it loops back upstream elements
      // to its upstream, and loops back downstream elementd to its downstream.

      val rotatedBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in1, new InHandler {
            override def onPush(): Unit = push(out2, grab(in1))

            override def onUpstreamFinish(): Unit = complete(out2)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = push(out1, grab(in2))

            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(out1, new OutHandler {
            override def onPull(): Unit = pull(in2)

            override def onDownstreamFinish(): Unit = cancel(in2)
          })

          setHandler(out2, new OutHandler {
            override def onPull(): Unit = pull(in1)

            override def onDownstreamFinish(): Unit = cancel(in1)
          })
        }

        override def toString = "IdentityBidi"
      }

      val takeAll = Flow[Int].grouped(200).toMat(Sink.head)(Keep.right)

      val (f1, f2) = RunnableGraph.fromGraph(GraphDSL.create(takeAll, takeAll)(Keep.both) { implicit b ⇒ (out1, out2) ⇒
        import GraphDSL.Implicits._
        val bidi = b.add(rotatedBidi)

        Source(1 to 10) ~> bidi.in1
        out2 <~ bidi.out2

        bidi.in2 <~ Source(1 to 100)
        bidi.out1 ~> out1
        ClosedShape
      }).run()

      Await.result(f1, 3.seconds) should ===(1 to 100)
      Await.result(f2, 3.seconds) should ===(1 to 10)
    }

    "be able to properly report errors if an error happens for an already completed stage" in {

      val failyStage = new GraphStage[SourceShape[Int]] {
        override val shape: SourceShape[Int] = new SourceShape(Outlet[Int]("test.out"))

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

          setHandler(shape.out, new OutHandler {
            override def onPull(): Unit = {
              completeStage()
              // This cannot be propagated now since the stage is already closed
              push(shape.out, -1)
            }
          })

        }
      }

      EventFilter[IllegalArgumentException](pattern = "Error in stage.*", occurrences = 1).intercept {
        Await.result(Source.fromGraph(failyStage).runWith(Sink.ignore), 3.seconds)
      }

    }

    "be able to properly handle case where a stage fails before subscription happens" in assertAllStagesStopped {

      // Fuzzing needs to be off, so that the failure can propagate to the output boundary before the ExposedPublisher
      // message.
      val noFuzzMat = ActorMaterializer(ActorMaterializerSettings(system).withFuzzing(false))

      val te = TE("Test failure in preStart")

      val evilLatch = new CountDownLatch(1)

      /*
       * This is a somewhat tricky test setup. We need the following conditions to be met:
       *  - the stage should fail its output port before the ExposedPublisher message is processed
       *  - the enclosing actor (and therefore the stage) should be kept alive until a stray SubscribePending arrives
       *    that has been enqueued after ExposedPublisher message has been enqueued, but before it has been processed
       *
       * To achieve keeping alive the stage for long enough, we use an extra input and output port and instead
       * of failing the stage, we fail only the output port under test.
       *
       * To delay the startup long enough, so both ExposedPublisher and SubscribePending are enqueued, we use an evil
       * latch to delay the preStart() (which in turn delays the enclosing actor's preStart).
       *
       */

      val failyStage = new GraphStage[FanOutShape2[Int, Int, Int]] {
        override val shape: FanOutShape2[Int, Int, Int] = new FanOutShape2(
          Inlet[Int]("test.in"),
          Outlet[Int]("test.out0"),
          Outlet[Int]("test.out1"))

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

          override def preStart(): Unit = {
            pull(shape.in)
            evilLatch.await()
            fail(shape.out0, te)
          }

          setHandler(shape.out0, ignoreTerminateOutput) //We fail in preStart anyway
          setHandler(shape.out1, ignoreTerminateOutput) //We fail in preStart anyway
          passAlong(shape.in, shape.out1)
        }
      }

      val downstream0 = TestSubscriber.probe[Int]()
      val downstream1 = TestSubscriber.probe[Int]()

      val upstream = TestPublisher.probe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val faily = b.add(failyStage)

        Source.fromPublisher(upstream) ~> faily.in
        faily.out0 ~> Sink.fromSubscriber(downstream0)
        faily.out1 ~> Sink.fromSubscriber(downstream1)

        ClosedShape
      }).run()(noFuzzMat)

      evilLatch.countDown()
      downstream0.expectSubscriptionAndError(te)

      // If an NPE would happen due to unset exposedPublisher (see #19338) this would receive a failure instead
      // of the actual element
      downstream1.request(1)
      upstream.sendNext(42)
      downstream1.expectNext(42)

      upstream.sendComplete()
      downstream1.expectComplete()

    }

    "be able to handle Publisher spec violations without leaking" in assertAllStagesStopped {
      val filthyPublisher = new Publisher[Int] {
        override def subscribe(s: Subscriber[_ >: Int]): Unit = {
          s.onSubscribe(new Subscription {
            override def cancel(): Unit = ()
            override def request(n: Long): Unit = throw TE("violating your spec")
          })
        }
      }

      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      Source.combine(
        Source.fromPublisher(filthyPublisher),
        Source.fromPublisher(upstream))(count ⇒ Merge(count))
        .runWith(Sink.fromSubscriber(downstream))

      upstream.ensureSubscription()
      upstream.expectCancellation()

      downstream.ensureSubscription()

      val ise = downstream.expectError()
      ise shouldBe an[IllegalStateException]
      ise.getCause shouldBe a[SpecViolation]
      ise.getCause.getCause shouldBe a[TE]
      ise.getCause.getCause should (have message ("violating your spec"))
    }

    "be able to handle Subscriber spec violations without leaking" in assertAllStagesStopped {
      val filthySubscriber = new Subscriber[Int] {
        override def onSubscribe(s: Subscription): Unit = s.request(1)
        override def onError(t: Throwable): Unit = ()
        override def onComplete(): Unit = ()
        override def onNext(t: Int): Unit = throw TE("violating your spec")
      }

      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      Source.fromPublisher(upstream)
        .alsoTo(Sink.fromSubscriber(downstream))
        .runWith(Sink.fromSubscriber(filthySubscriber))

      upstream.sendNext(0)

      downstream.requestNext(0)
      val ise = downstream.expectError()
      ise shouldBe an[IllegalStateException]
      ise.getCause shouldBe a[SpecViolation]
      ise.getCause.getCause shouldBe a[TE]
      ise.getCause.getCause should (have message ("violating your spec"))

      upstream.expectCancellation()
    }

  }
}
