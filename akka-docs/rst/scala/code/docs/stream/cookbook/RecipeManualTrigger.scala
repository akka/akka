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
      val triggerSource = Source.fromPublisher(pub)
      val sink = Sink.fromSubscriber(sub)

      //#manually-triggered-stream
      val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._
        val zip = builder.add(Zip[Message, Trigger]())
        elements ~> zip.in0
        triggerSource ~> zip.in1
        zip.out ~> Flow[(Message, Trigger)].map { case (msg, trigger) => msg } ~> sink
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
      val triggerSource = Source.fromPublisher(pub)
      val sink = Sink.fromSubscriber(sub)

      //#manually-triggered-stream-zipwith
      val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._
        val zip = builder.add(ZipWith((msg: Message, trigger: Trigger) => msg))

        elements ~> zip.in0
        triggerSource ~> zip.in1
        zip.out ~> sink
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
