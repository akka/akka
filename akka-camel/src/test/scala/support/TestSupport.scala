package se.scalablesolutions.akka.camel.support

import java.util.concurrent.{TimeUnit, CountDownLatch}

import se.scalablesolutions.akka.camel.Message
import se.scalablesolutions.akka.actor.Actor

import TestSupport._

object TestSupport {
  type Handler = PartialFunction[Any, Any]
}

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
  var message: Any = _

  def retain: Handler = {
    case GetRetainedMessage => self.reply(message)
    case msg => {
      message = msg
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
