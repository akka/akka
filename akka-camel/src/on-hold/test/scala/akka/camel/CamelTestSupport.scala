package akka.camel

import java.util.concurrent.CountDownLatch

import collection.mutable.Buffer

import akka.actor.Actor

object CamelTestSupport {
  type Handler = PartialFunction[Any, Any]

  trait TestActor extends Actor {
    def receive = {
      case msg => {
        handler(msg)
      }
    }

    def handler: Handler
  }

  class Tester1 extends TestActor with Retain with Countdown {
    def handler = retain andThen countdown
  }

  class Tester2 extends TestActor with Respond {
    def handler = respond
  }

  class Tester3 extends TestActor with Noop {
    self.timeout = 1
    def handler = noop
  }

  trait Countdown { this: Actor =>
    var latch: CountDownLatch = new CountDownLatch(0)
    def countdown: Handler = {
      case SetExpectedMessageCount(num) => {
        latch = new CountDownLatch(num)
        self.reply(latch)
      }
      case msg => latch.countDown
    }
  }

  trait Respond { this: Actor =>
    def respond: Handler = {
      case msg: Message => self.reply(response(msg))
    }

    def response(msg: Message): Any = "Hello %s" format msg.body
  }

  trait Retain { this: Actor =>
    val messages = Buffer[Any]()

    def retain: Handler = {
      case GetRetainedMessage     => self.reply(messages.last)
      case GetRetainedMessages(p) => self.reply(messages.toList.filter(p))
      case msg => {
        messages += msg
        msg
      }
    }
  }

  trait Noop  { this: Actor =>
    def noop: Handler = {
      case msg => msg
    }
  }

  case class SetExpectedMessageCount(num: Int)
  case class GetRetainedMessage()
  case class GetRetainedMessages(p: Any => Boolean) {
    def this() = this(_ => true)
  }
}

