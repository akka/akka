/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.security.MessageDigest
import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, DurationInt }
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.{ Actor, ActorContext, ActorLogging, ActorRef, Props, ReceiveTimeout, Stash, Terminated }
import akka.io.TcpPipelineHandler.{ Init, Management, WithinActorContext }
import akka.pattern.ask
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.util.{ ByteString, Timeout }
import akka.actor.Deploy

object BackpressureSpec {

  final val ChunkSize = 1024

  case class StartSending(n: Int)
  case class Done(hash: ByteString)
  case object Failed
  case object Close

  class Sender(receiver: InetSocketAddress) extends Actor with Stash with ActorLogging {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()

    import context.system
    IO(Tcp) ! Tcp.Connect(receiver)

    def receive = {
      case _: Tcp.Connected ⇒
        val init = TcpPipelineHandler.withLogger(log,
          new TcpReadWriteAdapter >>
            new BackpressureBuffer(10000, 1000000, Long.MaxValue))
        val handler = context.actorOf(TcpPipelineHandler(init, sender, self).withDeploy(Deploy.local), "pipeline")
        sender ! Tcp.Register(handler)
        unstashAll()
        context.become(connected(init, handler))

      case _: Tcp.CommandFailed ⇒
        unstashAll()
        context.become(failed)

      case _ ⇒ stash()
    }

    def connected(init: Init[WithinActorContext, ByteString, ByteString], connection: ActorRef): Receive = {
      case StartSending(0) ⇒ sender ! Done(ByteString(digest.digest()))
      case StartSending(n) ⇒
        val rnd = ThreadLocalRandom.current
        val data = Array.tabulate[Byte](ChunkSize)(_ ⇒ rnd.nextInt().toByte)
        digest.update(data)
        connection ! init.Command(ByteString(data))
        self forward StartSending(n - 1)
      case BackpressureBuffer.HighWatermarkReached ⇒
        context.setReceiveTimeout(5.seconds)
        context.become({
          case BackpressureBuffer.LowWatermarkReached ⇒
            unstashAll()
            context.setReceiveTimeout(Duration.Undefined)
            context.unbecome()
          case ReceiveTimeout ⇒
            log.error("receive timeout while throttled")
            context.stop(self)
          case _ ⇒ stash()
        }, discardOld = false)
      case ReceiveTimeout ⇒ // that old cancellation race
      case Close          ⇒ connection ! Management(Tcp.Close)
      case Tcp.Closed     ⇒ context.stop(self)
    }

    val failed: Receive = {
      case _ ⇒ sender ! Failed
    }

    override def postRestart(thr: Throwable): Unit = context.stop(self)
  }

  case object GetPort
  case class Port(p: Int)
  case object GetProgress
  case class Progress(n: Int)
  case object GetHash
  case class Hash(hash: ByteString)

  class Receiver(hiccups: Boolean) extends Actor with Stash with ActorLogging {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()

    import context.system
    IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress("localhost", 0))

    var listener: ActorRef = _

    def receive = {
      case Tcp.Bound(local) ⇒
        listener = sender
        unstashAll()
        context.become(bound(local.getPort))
      case _: Tcp.CommandFailed ⇒
        unstashAll()
        context.become(failed)
      case _ ⇒ stash()
    }

    def bound(port: Int): Receive = {
      case GetPort ⇒ sender ! Port(port)
      case Tcp.Connected(local, remote) ⇒
        val init = TcpPipelineHandler.withLogger(log,
          new TcpReadWriteAdapter >>
            new BackpressureBuffer(10000, 1000000, Long.MaxValue))
        val handler = context.actorOf(TcpPipelineHandler(init, sender, self).withDeploy(Deploy.local), "pipeline")
        sender ! Tcp.Register(handler)
        unstashAll()
        context.become(connected(init, handler))
      case _ ⇒ stash()
    }

    def connected(init: Init[WithinActorContext, ByteString, ByteString], connection: ActorRef): Receive = {
      var received = 0L

      {
        case init.Event(data) ⇒
          digest.update(data.toArray)
          received += data.length
          if (hiccups && ThreadLocalRandom.current.nextInt(1000) == 0) {
            connection ! Management(Tcp.SuspendReading)
            import context.dispatcher
            system.scheduler.scheduleOnce(100.millis, connection, Management(Tcp.ResumeReading))
          }
        case GetProgress ⇒
          sender ! Progress((received / ChunkSize).toInt)
        case GetHash ⇒
          sender ! Hash(ByteString(digest.digest()))
        case Tcp.PeerClosed ⇒
          listener ! Tcp.Unbind
          context.become {
            case Tcp.Unbound ⇒ context.stop(self)
          }
      }
    }

    val failed: Receive = {
      case _ ⇒ sender ! Failed
    }

    override def postRestart(thr: Throwable): Unit = context.stop(self)
  }
}

class BackpressureSpec extends AkkaSpec("akka.actor.serialize-creators=on") with ImplicitSender {

  import BackpressureSpec._

  "A BackpressureBuffer" must {

    "transmit the right bytes" in {
      val N = 100000
      val recv = watch(system.actorOf(Props(classOf[Receiver], false), "receiver1"))
      recv ! GetPort
      val port = expectMsgType[Port].p
      val send = watch(system.actorOf(Props(classOf[Sender], new InetSocketAddress("localhost", port)), "sender1"))
      within(20.seconds) {
        send ! StartSending(N)
        val hash = expectMsgType[Done].hash
        implicit val t = Timeout(100.millis)
        awaitAssert(Await.result(recv ? GetProgress, t.duration) must be === Progress(N))
        recv ! GetHash
        expectMsgType[Hash].hash must be === hash
      }
      send ! Close
      val terminated = receiveWhile(1.second, messages = 2) {
        case Terminated(t) ⇒ t
      }
      terminated.toSet must be === Set(send, recv)
    }

    "transmit the right bytes with hiccups" in {
      val N = 100000
      val recv = watch(system.actorOf(Props(classOf[Receiver], true), "receiver2"))
      recv ! GetPort
      val port = expectMsgType[Port].p
      val send = watch(system.actorOf(Props(classOf[Sender], new InetSocketAddress("localhost", port)), "sender2"))
      within(20.seconds) {
        send ! StartSending(N)
        val hash = expectMsgType[Done].hash
        implicit val t = Timeout(100.millis)
        awaitAssert(Await.result(recv ? GetProgress, t.duration) must be === Progress(N))
        recv ! GetHash
        expectMsgType[Hash].hash must be === hash
      }
      send ! Close
      val terminated = receiveWhile(1.second, messages = 2) {
        case Terminated(t) ⇒ t
      }
      terminated.toSet must be === Set(send, recv)
    }

  }

}