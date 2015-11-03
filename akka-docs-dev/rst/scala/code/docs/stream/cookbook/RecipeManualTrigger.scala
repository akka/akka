package docs.stream.cookbook

import akka.stream.ClosedShape
import akka.stream.scaladsl._
import akka.stream.testkit._
import scala.concurrent.duration._

class RecipeManualTrigger extends RecipeSpec {

  "Recipe for triggering a stream manually" must {

    "work" in {

      val elements = Source(List("1", "2", "3", "4"))
      val pub = TestPublisher.probe[Trigger]()
      val sub = TestSubscriber.manualProbe[Message]()
      val triggerSource = Source(pub)
      val sink = Sink(sub)

      //#manually-triggered-stream
      val graph = RunnableGraph.fromGraph(FlowGraph.create() { implicit b =>
        import FlowGraph.Implicits._
        val zip = b.add(Zip[Message, Trigger]())
        val extractMsg = b.add(Flow[(Message, Trigger)].map { case (msg, trigger) => msg })

        b.add(elements) ~> zip.in0
        b.add(triggerSource) ~> zip.in1
        zip.out ~> extractMsg ~> b.add(sink)
        ClosedShape
      })
      //#manually-triggered-stream

      graph.run()

      sub.expectSubscription().request(1000)
      sub.expectNoMsg(100.millis)

      pub.sendNext(())
      sub.expectNext("1")
      sub.expectNoMsg(100.millis)

      pub.sendNext(())
      pub.sendNext(())
      sub.expectNext("2")
      sub.expectNext("3")
      sub.expectNoMsg(100.millis)

      pub.sendNext(())
      sub.expectNext("4")
      sub.expectComplete()
    }

    "work with ZipWith" in {

      val elements = Source(List("1", "2", "3", "4"))
      val pub = TestPublisher.probe[Trigger]()
      val sub = TestSubscriber.manualProbe[Message]()
      val triggerSource = Source(pub)
      val sink = Sink(sub)

      //#manually-triggered-stream-zipwith
      val graph = RunnableGraph.fromGraph(FlowGraph.create() { implicit builder =>
        import FlowGraph.Implicits._
        val zip = builder.add(ZipWith((msg: Message, trigger: Trigger) => msg))
        val addedElements = builder.add(elements)
        val addedTriggerSource = builder.add(triggerSource)
        val addedSink = builder.add(sink)

        addedElements ~> zip.in0
        addedTriggerSource ~> zip.in1
        zip.out ~> addedSink
        ClosedShape
      })
      //#manually-triggered-stream-zipwith

      graph.run()

      sub.expectSubscription().request(1000)
      sub.expectNoMsg(100.millis)

      pub.sendNext(())
      sub.expectNext("1")
      sub.expectNoMsg(100.millis)

      pub.sendNext(())
      pub.sendNext(())
      sub.expectNext("2")
      sub.expectNext("3")
      sub.expectNoMsg(100.millis)

      pub.sendNext(())
      sub.expectNext("4")
      sub.expectComplete()
    }

  }

}
