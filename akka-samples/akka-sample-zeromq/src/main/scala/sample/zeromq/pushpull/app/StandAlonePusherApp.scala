package sample.zeromq.pushpull.app

import org.zeromq.ZMQ
import sample.zeromq.Util

object StandAlonePusherApp extends App {

  val context = ZMQ.context(1)
  val socket = context.socket(ZMQ.PUSH)
  socket.bind("tcp://127.0.0.1:1234")

  val maxMessageSize = 100

  while (true) {
    val message = Util.randomString(maxMessageSize)
    socket.send(message.getBytes("UTF-8"), 0)
  }
}
