package sample.remote.benchmark

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.ReceiveTimeout
import akka.actor.Terminated
import akka.actor.Props
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object Sender {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem("Sys", ConfigFactory.load("calculator"))

    val remoteHostPort = if (args.length >= 1) args(0) else "127.0.0.1:2553"
    val remotePath = s"akka.tcp://Sys@$remoteHostPort/user/rcv"
    val totalMessages = if (args.length >= 2) args(1).toInt else 500000
    val burstSize = if (args.length >= 3) args(2).toInt else 5000
    val payloadSize = if (args.length >= 4) args(3).toInt else 100

    system.actorOf(Sender.props(remotePath, totalMessages, burstSize, payloadSize), "snd")
  }

  def props(path: String, totalMessages: Int, burstSize: Int, payloadSize: Int): Props =
    Props(new Sender(path, totalMessages, burstSize, payloadSize))

  private case object Warmup
  case object Shutdown
  sealed trait Echo
  case object Start extends Echo
  case object Done extends Echo
  case class Continue(remaining: Int, startTime: Long, burstStartTime: Long, n: Int)
    extends Echo
}

class Sender(path: String, totalMessages: Int, burstSize: Int, payloadSize: Int) extends Actor {
  import Sender._

  val payload: Array[Byte] = Vector.fill(payloadSize)("a").mkString.getBytes
  var startTime = 0L
  var maxRoundTripMillis = 0L

  context.setReceiveTimeout(3.seconds)
  sendIdentifyRequest()

  def sendIdentifyRequest(): Unit =
    context.actorSelection(path) ! Identify(path)

  def receive = identifying

  def identifying: Receive = {
    case ActorIdentity(`path`, Some(actor)) =>
      context.watch(actor)
      context.become(active(actor))
      context.setReceiveTimeout(Duration.Undefined)
      self ! Warmup
    case ActorIdentity(`path`, None) => println(s"Remote actor not available: $path")
    case ReceiveTimeout              => sendIdentifyRequest()
  }

  def active(actor: ActorRef): Receive = {
    case Warmup =>
      sendBatch(actor, burstSize)
      actor ! Start

    case Start =>
      println(s"Starting benchmark of $totalMessages messages with burst size $burstSize and payload size $payloadSize")
      startTime = System.nanoTime
      val remaining = sendBatch(actor, totalMessages)
      if (remaining == 0)
        actor ! Done
      else
        actor ! Continue(remaining, startTime, startTime, burstSize)

    case c @ Continue(remaining, t0, t1, n) =>
      val now = System.nanoTime()
      val duration = (now - t0).nanos.toMillis
      val roundTripMillis = (now - t1).nanos.toMillis
      maxRoundTripMillis = math.max(maxRoundTripMillis, roundTripMillis)
      if (duration >= 500) {
        val throughtput = (n * 1000.0 / duration).toInt
        println(s"It took $duration ms to deliver $n messages, throughtput $throughtput msg/s, " +
          s"latest round-trip $roundTripMillis ms, remaining $remaining of $totalMessages")
      }

      val nextRemaining = sendBatch(actor, remaining)
      if (nextRemaining == 0)
        actor ! Done
      else if (duration >= 500)
        actor ! Continue(nextRemaining, now, now, burstSize)
      else
        actor ! c.copy(remaining = nextRemaining, burstStartTime = now, n = n + burstSize)

    case Done =>
      val took = (System.nanoTime - startTime).nanos.toMillis
      val throughtput = (totalMessages * 1000.0 / took).toInt
      println(s"== It took $took ms to deliver $totalMessages messages, throughtput $throughtput msg/s, " +
        s"max round-trip $maxRoundTripMillis ms, burst size $burstSize, " +
        s"payload size $payloadSize")
      actor ! Shutdown

    case Terminated(`actor`) =>
      println("Receiver terminated")
      context.system.terminate()

  }

  /**
   * @return remaining messages after sending the batch
   */
  def sendBatch(actor: ActorRef, remaining: Int): Int = {
    val batchSize = math.min(remaining, burstSize)
    (1 to batchSize) foreach { x => actor ! payload }
    remaining - batchSize
  }
}

