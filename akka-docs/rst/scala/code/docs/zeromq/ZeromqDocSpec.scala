/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.zeromq

import language.postfixOps

import akka.actor.{ Actor, Props }
import scala.concurrent.duration._
import akka.testkit._
import akka.zeromq.{ ZeroMQVersion, ZeroMQExtension }
import java.text.SimpleDateFormat
import java.util.Date
import akka.zeromq.{ SocketType, Bind }

object ZeromqDocSpec {

  //#health
  import akka.zeromq._
  import akka.actor.Actor
  import akka.actor.Props
  import akka.actor.ActorLogging
  import akka.serialization.SerializationExtension
  import java.lang.management.ManagementFactory

  case object Tick
  case class Heap(timestamp: Long, used: Long, max: Long)
  case class Load(timestamp: Long, loadAverage: Double)

  class HealthProbe extends Actor {

    val pubSocket = ZeroMQExtension(context.system).newSocket(SocketType.Pub,
      Bind("tcp://127.0.0.1:1235"))
    val memory = ManagementFactory.getMemoryMXBean
    val os = ManagementFactory.getOperatingSystemMXBean
    val ser = SerializationExtension(context.system)
    import context.dispatcher

    override def preStart() {
      context.system.scheduler.schedule(1 second, 1 second, self, Tick)
    }

    override def postRestart(reason: Throwable) {
      // don't call preStart, only schedule once
    }

    def receive: Receive = {
      case Tick ⇒
        val currentHeap = memory.getHeapMemoryUsage
        val timestamp = System.currentTimeMillis

        // use akka SerializationExtension to convert to bytes
        val heapPayload = ser.serialize(Heap(timestamp, currentHeap.getUsed,
          currentHeap.getMax)).get
        // the first frame is the topic, second is the message
        pubSocket ! ZMQMessage(Seq(Frame("health.heap"), Frame(heapPayload)))

        // use akka SerializationExtension to convert to bytes
        val loadPayload = ser.serialize(Load(timestamp, os.getSystemLoadAverage)).get
        // the first frame is the topic, second is the message
        pubSocket ! ZMQMessage(Seq(Frame("health.load"), Frame(loadPayload)))
    }
  }
  //#health

  //#logger
  class Logger extends Actor with ActorLogging {

    ZeroMQExtension(context.system).newSocket(SocketType.Sub, Listener(self),
      Connect("tcp://127.0.0.1:1235"), Subscribe("health"))
    val ser = SerializationExtension(context.system)
    val timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS")

    def receive = {
      // the first frame is the topic, second is the message
      case m: ZMQMessage if m.firstFrameAsString == "health.heap" ⇒
        val Heap(timestamp, used, max) = ser.deserialize(m.payload(1),
          classOf[Heap]).get
        log.info("Used heap {} bytes, at {}", used,
          timestampFormat.format(new Date(timestamp)))

      case m: ZMQMessage if m.firstFrameAsString == "health.load" ⇒
        val Load(timestamp, loadAverage) = ser.deserialize(m.payload(1),
          classOf[Load]).get
        log.info("Load average {}, at {}", loadAverage,
          timestampFormat.format(new Date(timestamp)))
    }
  }
  //#logger

  //#alerter
  class HeapAlerter extends Actor with ActorLogging {

    ZeroMQExtension(context.system).newSocket(SocketType.Sub,
      Listener(self), Connect("tcp://127.0.0.1:1235"), Subscribe("health.heap"))
    val ser = SerializationExtension(context.system)
    var count = 0

    def receive = {
      // the first frame is the topic, second is the message
      case m: ZMQMessage if m.firstFrameAsString == "health.heap" ⇒
        val Heap(timestamp, used, max) = ser.deserialize(m.payload(1),
          classOf[Heap]).get
        if ((used.toDouble / max) > 0.9) count += 1
        else count = 0
        if (count > 10) log.warning("Need more memory, using {} %",
          (100.0 * used / max))
    }
  }
  //#alerter

}

class ZeromqDocSpec extends AkkaSpec("akka.loglevel=INFO") {
  import ZeromqDocSpec._

  "demonstrate how to create socket" in {
    checkZeroMQInstallation()

    //#pub-socket
    import akka.zeromq.ZeroMQExtension
    val pubSocket = ZeroMQExtension(system).newSocket(SocketType.Pub,
      Bind("tcp://127.0.0.1:21231"))
    //#pub-socket

    //#sub-socket
    import akka.zeromq._
    val listener = system.actorOf(Props(new Actor {
      def receive: Receive = {
        case Connecting    ⇒ //...
        case m: ZMQMessage ⇒ //...
        case _             ⇒ //...
      }
    }))
    val subSocket = ZeroMQExtension(system).newSocket(SocketType.Sub,
      Listener(listener), Connect("tcp://127.0.0.1:21231"), SubscribeAll)
    //#sub-socket

    //#sub-topic-socket
    val subTopicSocket = ZeroMQExtension(system).newSocket(SocketType.Sub,
      Listener(listener), Connect("tcp://127.0.0.1:21231"), Subscribe("foo.bar"))
    //#sub-topic-socket

    //#unsub-topic-socket
    subTopicSocket ! Unsubscribe("foo.bar")
    //#unsub-topic-socket

    val payload = Array.empty[Byte]
    //#pub-topic
    pubSocket ! ZMQMessage(Seq(Frame("foo.bar"), Frame(payload)))
    //#pub-topic

    system.stop(subSocket)
    system.stop(subTopicSocket)

    //#high-watermark
    val highWatermarkSocket = ZeroMQExtension(system).newSocket(
      SocketType.Router,
      Listener(listener),
      Bind("tcp://127.0.0.1:21233"),
      HighWatermark(50000))
    //#high-watermark
  }

  "demonstrate pub-sub" in {
    checkZeroMQInstallation()

    //#health

    system.actorOf(Props[HealthProbe], name = "health")
    //#health

    //#logger

    system.actorOf(Props[Logger], name = "logger")
    //#logger

    //#alerter

    system.actorOf(Props[HeapAlerter], name = "alerter")
    //#alerter

    // Let it run for a while to see some output.
    // Don't do like this in real tests, this is only doc demonstration.
    Thread.sleep(3.seconds.toMillis)

  }

  def checkZeroMQInstallation() = try {
    ZeroMQExtension(system).version match {
      case ZeroMQVersion(2, 1, _) ⇒ Unit
      case version                ⇒ pending
    }
  } catch {
    case e: LinkageError ⇒ pending
  }
}
