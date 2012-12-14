package sample.zeromq.reqrep.app

import org.zeromq.ZMQ

/**
 * A stand alone replyer. It does not use Akka and
 * systematically replies the same message it received.
 */
object StandAloneReplyerApp extends App {
  val host = "tcp://127.0.0.1:1234"

  val context = ZMQ.context(1)
  val socket = context.socket(ZMQ.REP)
  socket.bind(host)

  while (true) {
    val res = socket.recv(0)
    socket.send(res, 0)
  }
}
