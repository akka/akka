package docs.stream.cookbook

import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.testkit._

import scala.concurrent.duration._

class RecipeSimpleDrop extends RecipeSpec {

  "Recipe for simply dropping elements for a faster stream" must {

    "work" in {

      //#simple-drop
      val droppyStream: Flow[Message, Message, Unit] =
        Flow[Message].conflate(seed = identity)((lastMessage, newMessage) => newMessage)
      //#simple-drop

      val pub = TestPublisher.probe[Message]()
      val sub = TestSubscriber.manualProbe[Message]()
      val messageSource = Source(pub)
      val sink = Sink(sub)

      messageSource.via(droppyStream).to(sink).run()

      val subscription = sub.expectSubscription()
      sub.expectNoMsg(100.millis)

      pub.sendNext("1")
      pub.sendNext("2")
      pub.sendNext("3")

      subscription.request(1)
      sub.expectNext("3")

      pub.sendComplete()
      subscription.request(1)
      sub.expectComplete()
    }

  }

}
