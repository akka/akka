package se.scalablesolutions.akka.camel.support

import java.util.concurrent.{TimeUnit, CountDownLatch}

import se.scalablesolutions.akka.camel.Message
import se.scalablesolutions.akka.actor.Actor

trait Receive[T] {
  def onMessage(msg: T): Unit
}

trait Respond extends Receive[Message] {self: Actor =>
  abstract override def onMessage(msg: Message): Unit = {
    super.onMessage(msg)
    reply(response(msg))
  }
  def response(msg: Message): Any = "Hello %s" format msg.body
}

trait Retain extends Receive[Message] {
  var body: Any = _
  var headers = Map.empty[String, Any]
  abstract override def onMessage(msg: Message): Unit = {
    super.onMessage(msg)
    body = msg.body
    headers = msg.headers
  }
}

trait Countdown[T] extends Receive[T] {
  val count = 1
  val duration = 5000
  val latch = new CountDownLatch(count)

  def waitFor = latch.await(duration, TimeUnit.MILLISECONDS)
  def countDown = latch.countDown

  abstract override def onMessage(msg: T) = {
    super.onMessage(msg)
    countDown
  }
}

class Tester extends Actor with Receive[Message] {
  def receive = {
    case msg: Message => onMessage(msg)
  }
  def onMessage(msg: Message): Unit = {}
}
