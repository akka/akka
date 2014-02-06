/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

import scala.concurrent.duration._

import com.typesafe.config._

import akka.actor._
import akka.persistence._

object ProcessorChannelRemoteExample {
  val config = ConfigFactory.parseString(
    """
      akka {
        actor {
          provider = "akka.remote.RemoteActorRefProvider"
        }
        remote {
          enabled-transports = ["akka.remote.netty.tcp"]
          netty.tcp.hostname = "127.0.0.1"
        }
        persistence {
          journal.leveldb.dir = "target/example/journal"
          snapshot-store.local.dir = "target/example/snapshots"
        }
        loglevel = INFO
        log-dead-letters = 0
        log-dead-letters-during-shutdown = off

      }
    """)
}

object SenderApp extends App {
  import ProcessorChannelRemoteExample._

  class ExampleProcessor(destination: ActorPath) extends Processor {
    val listener = context.actorOf(Props[ExampleListener])
    val channel = context.actorOf(Channel.props(ChannelSettings(
      redeliverMax = 5,
      redeliverInterval = 1.second,
      redeliverFailureListener = Some(listener))), "channel")

    def receive = {
      case p @ Persistent(payload, _) =>
        println(s"[processor] received payload: ${payload} (replayed = ${recoveryRunning})")
        channel ! Deliver(p.withPayload(s"processed ${payload}"), destination)
      case "restart" =>
        throw new Exception("restart requested")
      case reply: String =>
        println(s"[processor] received reply: ${reply}")
    }
  }

  class ExampleListener extends Actor {
    def receive = {
      case RedeliverFailure(messages) =>
        println(s"unable to deliver ${messages.length} messages, restarting processor to resend messages ...")
        context.parent ! "restart"
    }
  }

  val receiverPath = ActorPath.fromString("akka.tcp://receiver@127.0.0.1:44317/user/receiver")
  val senderConfig = ConfigFactory.parseString("""
      akka.persistence.journal.leveldb.dir = "target/example/journal"
      akka.persistence.snapshot-store.local.dir = "target/example/snapshots"
      akka.remote.netty.tcp.port = 44316
    """)

  val system = ActorSystem("sender", config.withFallback(senderConfig))
  val sender = system.actorOf(Props(classOf[ExampleProcessor], receiverPath))

  @annotation.tailrec
  def read(line: String): Unit = line match {
    case "exit" | null =>
    case msg =>
      sender ! Persistent(msg)
      read(Console.readLine())
  }

  read(Console.readLine())
  system.shutdown()

}

object ReceiverApp extends App {
  import ProcessorChannelRemoteExample._

  class ExampleDestination extends Actor {
    def receive = {
      case p @ ConfirmablePersistent(payload, snr, _) =>
        println(s"[destination] received payload: ${payload}")
        sender ! s"re: ${payload} (snr = ${snr})"
        p.confirm()
    }
  }

  val receiverConfig = ConfigFactory.parseString("akka.remote.netty.tcp.port = 44317")
  val system = ActorSystem("receiver", config.withFallback(receiverConfig))

  system.actorOf(Props[ExampleDestination], "receiver")
}