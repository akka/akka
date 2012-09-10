package sample.zeromq.reqrep.actor

import akka.actor.{ Terminated, ActorSystem, Props, Actor }
import util.Random
import akka.zeromq._
import scala.Array
import akka.zeromq.Listener
import akka.zeromq.Connect
import sample.zeromq.Util
import compat.Platform

class RequestActor extends Actor {
  println("Connecting...")

  private val requestSocket = ZeroMQExtension(context.system).newReqSocket(
    Array(
      Connect("tcp://127.0.0.1:1234"),
      Listener(self)))

  val random = new Random()
  val maxMessageSize = 10
  val numMessages = 20000

  var requestCount = 0
  var currentMessage = ""
  var startTime = Platform.currentTime

  def receive = {
    case m: ZMQMessage ⇒ {
      val text = new String(m.frames.head.payload.toArray)
      if (!text.equals(currentMessage)) {
        throw new Exception("The message was not received properly")
      }
      requestCount = requestCount + 1
      if (requestCount < numMessages) {
        sendRequest()
      } else {
        val endTime = Platform.currentTime
        val durationInSeconds = (endTime - startTime).toDouble / 1000
        println("Duration: " + durationInSeconds.toString)
        println("Throughput: " + (numMessages.toDouble / durationInSeconds).toString)
        context.system.shutdown()
      }
    }
    case Connecting ⇒ startTime = Platform.currentTime; sendRequest()
    case _          ⇒ ()
  }

  private def sendRequest() = {
    currentMessage = Util.randomString(random, maxMessageSize)
    requestSocket ! ZMQMessage(Seq(Frame(currentMessage)))
  }

}
