/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import collection.JavaConversions
import java.lang.Throwable
import akka.event.Logging
import akka.pattern.ask
import com.rabbitmq.client.{ ShutdownSignalException, Channel, ShutdownListener }
import scala.PartialFunction
import akka.util.duration._
import akka.util.Timeout
import akka.dispatch.{ Future, Await }
import akka.actor.{ Kill, Failed, ActorRef, Actor }

abstract private[amqp] class FaultTolerantChannelActor(
  exchangeParameters: Option[ExchangeParameters], channelParameters: Option[ChannelParameters]) extends Actor {

  protected[amqp] var channel: Option[Channel] = None

  val settings = Settings(context.system)
  implicit val timeout = Timeout(settings.Timeout)

  val log = Logging(context.system, this)

  override def receive = channelMessageHandler orElse specificMessageHandler

  // to be defined in subclassing actor
  def specificMessageHandler: PartialFunction[Any, Unit]

  private def channelMessageHandler: PartialFunction[Any, Unit] = {

    case Start ⇒ {
      // ask the connection for a new channel
      context.parent ? ChannelRequest map (_.asInstanceOf[Channel]) map setupChannelInternal
    }

    case ch: Channel ⇒ {
      setupChannelInternal(ch)
    }

    case ChannelShutdown(cause) ⇒ {
      closeChannel
      if (cause.isHardError) {
        // connection error
        if (cause.isInitiatedByApplication) {
          log.info("{} got normal shutdown", self.toString)
        } else {
          log.error(cause, "{} got hard error", self.toString)
        }
      } else {
        // channel error
        log.error(cause, "{} self restarting because of channel shutdown", self.toString)
        notifyCallback(Restarting)
        self ! Start
      }
    }

    case Failure(cause) ⇒
      log.error(cause, "{} self-restarting because of channel failure", self.toString)
      self ! Kill
  }

  // to be defined in subclassing actor
  protected def setupChannel(ch: Channel)

  private def setupChannelInternal(ch: Channel) = {
    if (channel.isEmpty) {
      //log.info("%s setting up channel" format toString)
      exchangeParameters.foreach {
        params ⇒
          import params._
          exchangeDeclaration match {
            case PassiveDeclaration ⇒ ch.exchangeDeclarePassive(exchangeName)
            case ActiveDeclaration(durable, autoDelete, _) ⇒
              ch.exchangeDeclare(exchangeName, exchangeType.toString, durable, autoDelete, JavaConversions.mapAsJavaMap(configurationArguments))
            case NoActionDeclaration ⇒ // ignore
          }
      }

      ch.addShutdownListener(new ShutdownListener {
        def shutdownCompleted(cause: ShutdownSignalException) = {
          self ! ChannelShutdown(cause)
        }
      })

      for (cp ← channelParameters; sdl ← cp.shutdownListener) ch.getConnection.addShutdownListener(sdl)

      setupChannel(ch)
      channel = Some(ch)
      notifyCallback(Started)
    } else {
      // close not needed channel
      if (ch.isOpen) ch.close()
    }
  }

  private def closeChannel(): Unit =
    channel = channel.flatMap(c ⇒ {
      if (c.isOpen) c.close()
      notifyCallback(Stopped)
      log.info("{} channel closed", self)
      None
    })

  private def notifyCallback(message: AMQPMessage): Unit =
    for (cp ← channelParameters; cb ← cp.channelCallback if !cb.isTerminated) cb ! message

  override def postRestart(reason: Throwable) {
    self ! Start
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    notifyCallback(Restarting)
    closeChannel
  }

  override def postStop = closeChannel
}
