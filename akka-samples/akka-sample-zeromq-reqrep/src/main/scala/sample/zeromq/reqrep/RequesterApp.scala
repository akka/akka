package sample.zeromq.reqrep

import org.zeromq.ZMQ
import util.Random
import compat.Platform

object RequesterApp extends App {

  val random = new Random()
  val maxMessageSize = 10
  val numMessages = 20
  val host = "tcp://127.0.0.1:1234"

  val context = ZMQ.context(1)
  val socket = context.socket(ZMQ.REQ)
  socket.connect(host)

  val startTime = Platform.currentTime
  for (_ ← 0 to numMessages) {
    val message = Util.randomString(random, maxMessageSize)
    socket.send(message.getBytes(), 0)
    val res = socket.recv(0)
  }
  val endTime = Platform.currentTime

  val durationInSeconds = (endTime - startTime).toDouble / 1000
  println("Duration: " + durationInSeconds.toString)
  println("Throughput: " + (numMessages.toDouble / durationInSeconds).toString)

}
