package docs.stream.cookbook

import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.{ SubscriberProbe, PublisherProbe }

import scala.concurrent.duration._

class RecipeSimpleDrop extends RecipeSpec {

  "Recipe for simply dropping elements for a faster stream" must {

    "work" in {

      //#simple-drop
      val droppyStream: Flow[Message, Message] =
        Flow[Message].conflate(seed = identity)((lastMessage, newMessage) => newMessage)
      //#simple-drop

      val pub = PublisherProbe[Message]()
      val sub = SubscriberProbe[Message]()
      val messageSource = Source(pub)
      val sink = Sink(sub)

      messageSource.via(droppyStream).to(sink).run()

      val manualSource = new StreamTestKit.AutoPublisher(pub)

      val subscription = sub.expectSubscription()
      sub.expectNoMsg(100.millis)

      manualSource.sendNext("1")
      manualSource.sendNext("2")
      manualSource.sendNext("3")

      subscription.request(1)
      sub.expectNext("3")

      manualSource.sendComplete()
      subscription.request(1)
      sub.expectComplete()
    }

  }

}
