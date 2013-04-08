package sample.zeromq.pushpull.app

import org.zeromq.ZMQ

object StandAlonePullerApp extends App {

  val host = "tcp://127.0.0.1:1234"

  val context = ZMQ.context(1)
  val socket = context.socket(ZMQ.PULL)
  socket.connect(host)

  var startTime = System.nanoTime
  var counter = 0
  while (true) {
    socket.recv(0)
    counter += 1
    if (counter >= 3000) {
      val current = System.nanoTime
      val span = (current - startTime).toDouble
      println("Rate: " + 1000 * counter.toDouble / span)
      startTime = current
      counter = 0
    }
  }
}
