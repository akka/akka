package sample.zeromq.reqrep.actor

import akka.actor.{ Terminated, ActorSystem, Props, Actor, ActorLogging }
import akka.util.ByteString
import akka.zeromq._
import scala.Array
import sample.zeromq.Util

class RequestActor extends Actor with ActorLogging {
  log.info("Connecting...")

  private val requestSocket = ZeroMQExtension(context.system).newReqSocket(
    Array(
      Connect("tcp://127.0.0.1:1234"),
      Listener(self)))

  val maxMessageSize = 10
  val numMessages = 20000

  var requestCount = 0
  var currentMessage = ""
  var startTime = System.nanoTime

  def receive = {
    case m: ZMQMessage ⇒ {
      val text = new String(m.frames.head.decodeString("UTF-8"))
      if (!text.equals(currentMessage)) {
        throw new Exception("The message was not received properly")
      }
      requestCount = requestCount + 1
      if (requestCount < numMessages) {
        sendRequest()
      } else {
        val endTime = System.nanoTime
        val durationInSeconds = (endTime - startTime).toDouble / 1000
        log.info("Duration: " + durationInSeconds.toString)
        log.info("Throughput: " + (numMessages.toDouble / durationInSeconds).toString)
        context.system.shutdown()
      }
    }
    case Connecting ⇒ startTime = System.nanoTime; sendRequest()
  }

  private def sendRequest() = {
    currentMessage = Util.randomString(maxMessageSize)
    requestSocket ! ZMQMessage(ByteString(currentMessage))
  }

}
