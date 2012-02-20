/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.amqp

import java.io.IOException
import com.rabbitmq.client._
import akka.event.Logging
import akka.actor._
import akka.util.duration._
import akka.amqp.AMQP.{ ConsumerParameters, ProducerParameters, ConnectionParameters }
import java.util.UUID

private[amqp] class FaultTolerantConnectionActor(connectionParameters: ConnectionParameters) extends Actor {
  import connectionParameters._

  val log = Logging(context.system, this)

  val connectionFactory: ConnectionFactory = new ConnectionFactory()
  connectionFactory.setUsername(username)
  connectionFactory.setPassword(password)
  connectionFactory.setVirtualHost(virtualHost)

  var connection: Option[Connection] = None
  var reconnectionFuture: Option[Cancellable] = None

  protected def receive = {
    case Connect    ⇒ connect
    case Disconnect ⇒ self ! Kill //disconnect
    case ChannelRequest ⇒ {
      connection match {
        case Some(conn) ⇒ {
          val channel: Channel = conn.createChannel
          sender ! Some(channel)
        }
        case None ⇒ {
          log.warning("Unable to create new channel - no connection")
          sender ! None
        }
      }
    }
    case ConnectionShutdown(cause) ⇒ {
      if (cause.isHardError) {
        // connection error
        if (cause.isInitiatedByApplication) {
          log.info("ConnectionShutdown by application [%s]" format self.path)
        } else {
          log.error(cause, "ConnectionShutdown is hard error - self terminating")
          throw new DeathPactException(self)
          //self ! Kill
        }
      }
    }

    case cr: ConsumerRequest ⇒ {
      connection match {
        case Some(conn) ⇒ {
          val consumer = newConsumer(cr.consumerParameters)
          sender ! Some(consumer)
        }
        case None ⇒ {
          log.warning("Unable to create new consumer - no connection")
          sender ! None
        }
      }
    }

    case pr: ProducerRequest ⇒ {
      connection match {
        case Some(conn) ⇒ {
          val producer = newProducer(pr.producerParameters)
          sender ! Some(producer)
        }
        case None ⇒ {
          log.warning("Unable to create new producer - no connection")
          sender ! None
        }
      }
    }
  }

  private def newProducer(producerParameters: ProducerParameters): ActorRef = {
    val producer = context.actorOf(Props(new ProducerActor(producerParameters)).withDispatcher("akka.actor.amqp-producer-dispatcher"), "amqp-producer-" + UUID.randomUUID().toString)
    producer ! Start
    producer
  }

  private def newConsumer(consumerParameters: ConsumerParameters): ActorRef = {
    val consumer = context.actorOf(Props(new ConsumerActor(consumerParameters)).withDispatcher("akka.actor.amqp-consumer-dispatcher"), "amqp-consumer-" + UUID.randomUUID().toString)
    consumer ! Start
    consumer
  }

  private def connect = if (connection.isEmpty || !connection.get.isOpen) {
    try {
      log.info("Connecting to one of [%s]" format addresses.toList)
      connection = Some(connectionFactory.newConnection(addresses))
      connection.foreach {
        conn ⇒
          {
            log.info("Connected to [%s]" format (conn.getAddress))
            conn.addShutdownListener(new ShutdownListener {
              def shutdownCompleted(cause: ShutdownSignalException) = {
                log.info("shutdownCompleted called, sending ConnectionShutdown message to {}", self.path.toString)
                log.info("cause = " + cause.getMessage)
                self ! ConnectionShutdown(cause)
              }
            })
            context.children.foreach(_ ! conn.createChannel)
            notifyCallback(Connected)
          }
      }
    } catch {
      case e: Exception ⇒
        log.error(e, "Unable to connect to one of [%s]" format addresses)
        log.info("Reconnecting in %d ms " format initReconnectDelay)
        connection = None
        reconnectionFuture = Some(context.system.scheduler.scheduleOnce(initReconnectDelay milliseconds) {
          notifyCallback(Reconnecting)
          self ! Connect
        })
    }
  }

  private def disconnect = {
    try {
      log.info("Disconnecting AMQP connection: " + connection)
      connection.foreach(_.close)
      notifyCallback(Disconnected)
    } catch {
      case e: IOException ⇒
        log.error(e, "Could not close AMQP connection")
      case _ ⇒ log.info("Connection closed")
    }
    connection = None
  }

  private def notifyCallback(message: AMQPMessage) = {
    connectionCallback.foreach(cb ⇒ if (!cb.isTerminated) cb ! message)
  }

  override def postStop = {
    log.debug("connection postStop triggered")
    // stop all reconnection attempts
    reconnectionFuture.foreach(_.cancel)
    // make sure postStop is called on all linked actors so they can do channel cleanup before connection is killed
    for (ref ← context.children) {
      context.stop(ref)
    }
    disconnect
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = disconnect

  override def postRestart(reason: Throwable) = {
    notifyCallback(Reconnecting)
    connect
  }
}
