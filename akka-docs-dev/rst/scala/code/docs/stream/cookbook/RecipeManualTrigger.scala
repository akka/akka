package docs.stream.cookbook

import akka.stream.scaladsl._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.{ SubscriberProbe, PublisherProbe }
import scala.concurrent.duration._

class RecipeManualTrigger extends RecipeSpec {

  "Recipe for triggering a stream manually" must {

    "work" in {

      val elements = Source(List("1", "2", "3", "4"))
      val pub = PublisherProbe[Trigger]()
      val sub = SubscriberProbe[Message]()
      val triggerSource = Source(pub)
      val sink = Sink(sub)

      //#manually-triggered-stream
      import FlowGraphImplicits._
      val graph = FlowGraph { implicit builder =>
        val zip = Zip[Message, Trigger]
        elements ~> zip.left
        triggerSource ~> zip.right
        zip.out ~> Flow[(Message, Trigger)].map { case (msg, trigger) => msg } ~> sink
      }
      //#manually-triggered-stream

      graph.run()
      val manualSource = new StreamTestKit.AutoPublisher(pub)

      sub.expectSubscription().request(1000)
      sub.expectNoMsg(100.millis)

      manualSource.sendNext(())
      sub.expectNext("1")
      sub.expectNoMsg(100.millis)

      manualSource.sendNext(())
      manualSource.sendNext(())
      sub.expectNext("2")
      sub.expectNext("3")
      sub.expectNoMsg(100.millis)

      manualSource.sendNext(())
      sub.expectNext("4")
      sub.expectComplete()
    }

    "work with ZipWith" in {

      val elements = Source(List("1", "2", "3", "4"))
      val pub = PublisherProbe[Trigger]()
      val sub = SubscriberProbe[Message]()
      val triggerSource = Source(pub)
      val sink = Sink(sub)

      //#manually-triggered-stream-zipwith
      import FlowGraphImplicits._
      val graph = FlowGraph { implicit builder =>
        val zip = ZipWith[Message, Trigger, Message](
          (msg: Message, trigger: Trigger) => msg)

        elements ~> zip.left
        triggerSource ~> zip.right
        zip.out ~> sink
      }
      //#manually-triggered-stream-zipwith

      graph.run()
      val manualSource = new StreamTestKit.AutoPublisher(pub)

      sub.expectSubscription().request(1000)
      sub.expectNoMsg(100.millis)

      manualSource.sendNext(())
      sub.expectNext("1")
      sub.expectNoMsg(100.millis)

      manualSource.sendNext(())
      manualSource.sendNext(())
      sub.expectNext("2")
      sub.expectNext("3")
      sub.expectNoMsg(100.millis)

      manualSource.sendNext(())
      sub.expectNext("4")
      sub.expectComplete()
    }

  }

}
