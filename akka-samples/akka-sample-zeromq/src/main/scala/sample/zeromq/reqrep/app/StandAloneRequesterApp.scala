package sample.zeromq.reqrep.app

import util.Random
import org.zeromq.ZMQ
import sample.zeromq.Util

/**
 * Makes a series of requests and check that the returned value
 * is the same that has been sent.
 */
object StandAloneRequesterApp extends App {
  val random = new Random()
  val maxMessageSize = 1000
  val numMessages = 20000
  val host = "tcp://127.0.0.1:1234"

  val context = ZMQ.context(1)
  val socket = context.socket(ZMQ.REQ)
  socket.connect(host)

  val startTime = System.nanoTime
  for (_ ← 0 to numMessages) {
    val message = Util.randomString(random, maxMessageSize)
    socket.send(message.getBytes("UTF-8"), 0)
    val res = socket.recv(0)
    if (!message.equals(new String(res, "UTF-8"))) {
      throw new Exception("Message not received properly")
    }
  }
  val endTime = System.nanoTime

  val durationInSeconds = (endTime - startTime).toDouble / 1000
  println("Duration: " + durationInSeconds.toString)
  println("Throughput: " + (numMessages.toDouble / durationInSeconds).toString)
}
