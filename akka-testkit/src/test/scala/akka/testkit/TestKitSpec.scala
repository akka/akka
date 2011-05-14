package akka.testkit

import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterEach, WordSpec}
import akka.actor._
import akka.config.Supervision.OneForOneStrategy
import akka.event.EventHandler
import akka.dispatch.Future
import akka.util.duration._

class TestKitSpec extends WordSpec with MustMatchers {

  "A TestKit" must {
  
    "reply to futures" in {
      val tk = new TestKit {}
      val future = tk.testActor.!!![Any]("hello")
      tk.expectMsg(0 millis, "hello") // TestActor runs on CallingThreadDispatcher
      tk.reply("world")
      future must be ('completed)
      future.get must equal ("world")
    }

    "reply to messages" in {
      val tk1 = new TestKit {}
      val tk2 = new TestKit {}
      tk1.testActor.!("hello")(Some(tk2.testActor))
      tk1.expectMsg(0 millis, "hello")
      tk1.reply("world")
      tk2.expectMsg(0 millis, "world")
    }
  
  }

}
