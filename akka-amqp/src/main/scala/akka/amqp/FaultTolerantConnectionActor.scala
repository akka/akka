/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import java.io.IOException
import com.rabbitmq.client._
import akka.event.Logging
import akka.actor._
import akka.util.duration._
import com.eaio.uuid.UUID
import akka.pattern.{ ask, pipe }
import akka.util.{ Timeout, Duration, NonFatal }
import akka.dispatch.{ Await, ExecutionContext, Future, Promise }

private[amqp] class FaultTolerantConnectionActor(connectionParameters: ConnectionParameters) extends Actor {

  import connectionParameters._

  val log = Logging(context.system, this)

  val settings = AMQP(context.system)

  val connectionFactory = {
    val c = new ConnectionFactory
    c setUsername username.getOrElse(settings.DefaultUser)
    c setPassword password.getOrElse(settings.DefaultPassword)
    c setVirtualHost virtualHost.getOrElse(settings.DefaultVhost)
    c
  }

  val addrs = Option(connectionParameters.addresses).filterNot(_.isEmpty).getOrElse(settings.DefaultAddresses)
  val reconnectDelay = connectionParameters.initReconnectDelay.getOrElse(settings.DefaultInitReconnectDelay)

  private var connection: Option[Future[Connection]] = None

  def receive = {
    /**
     * if we don't have a connection, or we do have one and it's not open, connect.  if we have a cancellable, then
     * there is a connection request already scheduled, so do nothing.
     */
    case Connect ⇒ if (connection.isEmpty) connect

    /**
     * consumers and producers send channel requests to the connection.  The response is a promise for a channel.
     */
    case ChannelRequest ⇒
      connection match {
        case Some(cf) ⇒ cf map (_.createChannel) pipeTo sender
        case None ⇒
          log.warning("Unable to create new channel - no connection")
          sender ! Status.Failure(new AkkaAMQPException("Unable to create new channel - no connection, establish one"))
      }

    /**
     * connection shutdown message received.  The cause is a ShutdownSignalException.  If the hardError flag is set,
     * it is a connection error, otherwise it is a channel error.  We will ignore channel errors here.  If the
     * isInitiatedByApplication flag is set, then the client requested this shutdown and we can go ahead and stop the
     * connection actor, otherwise we should throw the exception so the parent c.
     */
    case ConnectionShutdown(cause) ⇒
      if (cause.isHardError) {
        if (cause.isInitiatedByApplication) {
          log.info("Connection shutdown by application [{}]", self.path)
          context stop self
        } else {
          log.error(cause, "Connection failure - throwing exception to supervisor")
          throw cause
        }
      }

    /**
     * the application has requested a new consumer associated with the connection.  The consumer actor extends
     * FaultTolerantChannelActor, and the underlying channel should be a Future to account for the possibility that
     * it has not started yet.
     */
    case cr: ConsumerRequest ⇒
      if (connection.isDefined) {
        val consumer = context.actorOf(Props(new ConsumerActor(cr.consumerParameters)).
          withDispatcher("akka.actor.amqp.consumer-dispatcher"), "amqp-consumer-" + (new UUID).toString)
        consumer.tell(Start, sender)
      } else {
        log.warning("Unable to create new consumer - no connection")
        sender ! Status.Failure(new AkkaAMQPException("Unable to create new producer - no connection, establish one"))
      }

    /**
     * the application has requested a new producer associated with the connection.  The producer actor extends
     * FaultTolerantChannelActor, and the underlying channel should be a Future to account for the possibility that
     * it has not started yet.
     */
    case pr: ProducerRequest ⇒
      if (connection.isDefined) {
        val producer = context.actorOf(Props(new ProducerActor(pr.producerParameters)).
          withDispatcher("akka.actor.amqp.producer-dispatcher"), "amqp-producer-" + (new UUID).toString)
        producer.tell(Start, sender)
      } else {
        log.warning("Unable to create new producer - no connection")
        sender ! Status.Failure(new AkkaAMQPException("Unable to create new producer - no connection, establish one"))
      }
  }

  private def connect: Unit = {
    val replyTo = self
    try {
      log.info("Connecting to one of [{}]", addrs)
      connection = Option(Future(connectionFactory.newConnection(addrs.toArray))(context.dispatcher))

      for (opt ← connection; c ← opt) {
        val x = c.createChannel()
        notifyCallback(Connected)
        log.info("Connected to [{}]", c.getAddress)

        /**
         * If the connection is externally shut down, we want to generated a message to the connection
         * actor so the cause can be evaluated, and the proper action taken.
         */
        c.addShutdownListener(new ShutdownListener {
          def shutdownCompleted(cause: ShutdownSignalException) = {
            if (!self.isTerminated) {
              log.info("shutdownCompleted called, sending ConnectionShutdown message to {}", self.path)
              log.info("cause = {}", cause.getMessage)
              replyTo ! ConnectionShutdown(cause)
            }
          }
        })
      }
    } catch {
      case e: IOException ⇒
        val replyTo = self
        log.error(e, "Unable to connect to any of [{}]", addrs)
        log.info("Connection {} reconnecting in {}", self.path, reconnectDelay)
        connection = None
        sender ! Status.Failure(new AkkaAMQPException("Unable to create new connection, reconnecting in " + reconnectDelay))
        context.system.scheduler.scheduleOnce(reconnectDelay) {
          notifyCallback(Reconnecting)
          replyTo ! Connect
        }
      case NonFatal(e) ⇒
        connection = None
        sender ! Status.Failure(new AkkaAMQPException("Unable to create new connection"))
        throw e
    }
  }

  private def disconnect: Unit =
    try {
      log.info("Disconnecting AMQP connection: {}", self.path)
      for (opt ← connection; c ← opt) c.close
      notifyCallback(Disconnected)
    } catch {
      case e: IOException ⇒
        sender ! Status.Failure(e)
        throw e
      case NonFatal(e) ⇒
        sender ! Status.Failure(new AkkaAMQPException("Unable to close AMQP connection[" + self.path + "]"))
        throw e
    } finally {
      connection = None
    }

  private def notifyCallback(message: AMQPMessage): Unit =
    for (cb ← connectionCallback if !cb.isTerminated) cb ! message

  override def preStart = {
    notifyCallback(Starting)
    self ! connect
  }

  override def postStop = {
    disconnect
    super.postStop
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = disconnect

  override def postRestart(reason: Throwable) = {
    notifyCallback(Reconnecting)
    self ! connect
  }
}
