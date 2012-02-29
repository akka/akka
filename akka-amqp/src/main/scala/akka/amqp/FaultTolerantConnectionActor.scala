/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import java.io.IOException
import com.rabbitmq.client._
import akka.event.Logging
import akka.actor._
import akka.util.duration._
import java.util.UUID
import akka.util.NonFatal

private[amqp] class FaultTolerantConnectionActor(connectionParameters: ConnectionParameters) extends Actor {
  import connectionParameters._

  val log = Logging(context.system, this)

  val connectionFactory = {
    val c = new ConnectionFactory
    c setUsername username
    c setPassword password
    c setVirtualHost virtualHost
    c
  }

  private var connectionStatus: Option[Either[Connection, Cancellable]] = None

  protected def receive = {
    case Connect    ⇒ if (connectionStatus.isEmpty || connectionStatus.get.left.toOption.map(!_.isOpen).getOrElse(false)) connect
    case Disconnect ⇒ context stop self
    case ChannelRequest ⇒ connectionStatus match {
      case Some(conn) ⇒ conn.fold(l ⇒ {
        val channel: Channel = l.createChannel
        sender ! channel
      }, r ⇒ ())
      case None ⇒ {
        log.warning("Unable to create new channel - no connection")
        sender ! akka.actor.Status.Failure(new AkkaAMQPException("Unable to create new channel - no connection"))
      }
    }
    case ConnectionShutdown(cause) ⇒
      if (cause.isHardError) {
        // connection error
        if (cause.isInitiatedByApplication) {
          log.info("ConnectionShutdown by application [%s]" format self.path)
        } else {
          log.error(cause, "ConnectionShutdown is hard error - self terminating")
          context.parent ! Failed(cause)
        }
      }
    case cr: ConsumerRequest ⇒ connectionStatus match {
      case Some(conn) ⇒ conn.left.map { l ⇒
        val consumer = context.actorOf(Props(new ConsumerActor(cr.consumerParameters)).
          withDispatcher("akka.actor.amqp-consumer-dispatcher"), "amqp-consumer-" + UUID.randomUUID().toString)
        consumer ! Start
        sender ! consumer
      }
      case None ⇒ log.warning("Unable to create new consumer - no connection")
    }
    case pr: ProducerRequest ⇒ connectionStatus match {
      case Some(conn) ⇒ conn.left.map { l ⇒
        val producer = context.actorOf(Props(new ProducerActor(pr.producerParameters)).
          withDispatcher("akka.actor.amqp-producer-dispatcher"), "amqp-producer-" + UUID.randomUUID().toString)
        producer ! Start
        sender ! producer
      }
      case None ⇒ log.warning("Unable to create new producer - no connection")
    }
  }

  // if the connection is empty or is defined but not open
  private def connect =
    try {
      log.info("Connecting to one of [{}]", addresses.toList)
      val connection = connectionFactory.newConnection(addresses.toArray)
      connectionStatus = Option(Left(connection))

      log.info("Connected to [{}]", connection.getAddress)
      connection.addShutdownListener(new ShutdownListener {
        def shutdownCompleted(cause: ShutdownSignalException) = {
          log.info("shutdownCompleted called, sending ConnectionShutdown message to {}", self.path.toString)
          log.info("cause = {}", cause.getMessage)
          self ! ConnectionShutdown(cause)
        }
      })
      context.children.foreach(_ ! connection.createChannel)
      notifyCallback(Connected)
    } catch {
      case e: Exception ⇒
        log.error(e, "Unable to connect to one of [%s]" format addresses)
        log.info("Reconnecting in %d ms " format initReconnectDelay)
        connectionStatus = Option(Right(context.system.scheduler.scheduleOnce(initReconnectDelay milliseconds) {
          notifyCallback(Reconnecting)
          self ! Connect
        }))
    }

  private def disconnect = {
    try {
      val connection = connectionStatus flatMap (_.left toOption)
      log.info("Disconnecting AMQP connection: " + connection)
      connection.foreach(_.close)
      notifyCallback(Disconnected)
    } catch {
      case e: IOException ⇒
        log.error(e, "Could not close AMQP connection")
      case NonFatal(_) ⇒ log.info("Connection closed")
    } finally {
      connectionStatus = None
    }
  }

  private def notifyCallback(message: AMQPMessage) = {
    connectionCallback.foreach(cb ⇒ if (!cb.isTerminated) cb ! message)
  }

  override def postStop = {
    // stop all reconnection attempts
    val reconnectionFuture = connectionStatus.flatMap(_.right.toOption)
    reconnectionFuture.foreach(_.cancel)
    disconnect
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = disconnect

  override def postRestart(reason: Throwable) = {
    notifyCallback(Reconnecting)
    connect
  }
}
