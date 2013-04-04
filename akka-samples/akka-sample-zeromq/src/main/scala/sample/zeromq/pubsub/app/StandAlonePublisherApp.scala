package sample.zeromq.pubsub.app

import org.zeromq.ZMQ
import sample.zeromq.Util

object StandAlonePublisherApp extends App {

  val context = ZMQ.context(1)
  val publisher = context.socket(ZMQ.PUB)
  publisher.bind("tcp://127.0.0.1:1234")

  val maxMessageSize = 100

  println("CTRL+C to quit.")
  while (true) {
    val message = Util.randomString(maxMessageSize)

    publisher.send(message.getBytes("UTF-8"), 0)
  }
}
