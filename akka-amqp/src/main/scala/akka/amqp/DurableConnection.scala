package akka.amqp

import com.rabbitmq.client._
import com.rabbitmq.client.{ Address ⇒ RmqAddress }
import akka.actor.FSM.{ CurrentState, Transition }
import akka.dispatch.Future
import java.lang.Thread
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ThreadFactory, Executors }
import akka.actor._
import akka.util.{ Duration, Timeout }
import akka.util.duration._

sealed trait ConnectionState
case object Disconnected extends ConnectionState
case object Connected extends ConnectionState

sealed trait ConnectionMessage
case object Connect extends ConnectionMessage
case object Disconnect extends ConnectionMessage
case class ConnectionCallback[T](callback: Connection ⇒ T) extends ConnectionMessage

private class ReconnectTimeoutGenerator {

  var currentTimeout = 1;
  var previousTimeout = 1
  def nextTimeoutSec(maxTimeoutSec: Int): Int = {
    if (currentTimeout < maxTimeoutSec) {
      val result = currentTimeout
      val nextTimeout = currentTimeout + previousTimeout
      currentTimeout = nextTimeout
      previousTimeout = result
      result
    } else {
      maxTimeoutSec
    }
  }

  def reset() {
    currentTimeout = 1
    previousTimeout = 1
  }
}

private[amqp] class DurableConnectionActor(connectionProperties: ConnectionProperties)
  extends Actor with FSM[ConnectionState, Option[Connection]] with ShutdownListener {

  val settings = AMQP(context.system)

  import connectionProperties._
  val connectionFactory = new ConnectionFactory()
  connectionFactory.setRequestedHeartbeat(amqpHeartbeat.toMillis.toInt)
  connectionFactory.setUsername(user)
  connectionFactory.setPassword(pass)
  connectionFactory.setVirtualHost(vhost)

  lazy val timeoutGenerator = new ReconnectTimeoutGenerator

  val executorService = Executors.newFixedThreadPool(channelThreads, new ThreadFactory {
    import connectionFactory._
    val uri = "amqp://%s@%s:%s%s".format(getUsername, getHost, getPort, getVirtualHost)
    val counter = new AtomicInteger()
    def newThread(r: Runnable) = {
      val t = new Thread(r)
      t.setDaemon(true)
      t.setName("%s:channel-%s".format(uri, counter.incrementAndGet()))
      t
    }
  })

  startWith(Disconnected, None)

  when(Disconnected) {
    case Event(Connect, _) ⇒
      log.info("Connecting to one of [{}]", addresses.mkString(", "))
      try {
        val connection = connectionFactory.newConnection(executorService, addresses.map(RmqAddress.parseAddress).toArray)
        connection.addShutdownListener(this)
        log.info("Successfully connected to {}", connection)
        cancelTimer("reconnect")
        timeoutGenerator.reset()
        goto(Connected) using Some(connection)
      } catch {
        case e: Exception ⇒
          log.error(e, "Error while trying to connect")
          val nextReconnectTimeout = timeoutGenerator.nextTimeoutSec(maxReconnectDelay.toSeconds.toInt)
          import akka.util.duration._
          setTimer("reconnect", Connect, nextReconnectTimeout seconds, true)
          log.info("Reconnecting in {} seconds...".format(nextReconnectTimeout))
          stay()
      }
    case Event(Disconnect, _) ⇒
      cancelTimer("reconnect")
      log.info("Already disconnected")
      stay()
  }

  when(Connected) {
    case Event(ConnectionCallback(callback), Some(connection)) ⇒
      stay() replying callback.apply(connection)
    case Event(Disconnect, Some(connection)) ⇒
      try {
        log.debug("Disconnecting from {}", connection)
        connection.close()
        log.info("Successfully disconnected from {}", connection)
      } catch {
        case e: Exception ⇒ log.error(e, "Error while closing connection")
      }
      goto(Disconnected)
    case Event(cause: ShutdownSignalException, Some(connection)) ⇒
      if (cause.isHardError) {
        log.error(cause, "Connection broke down {}", connection)
        self ! Connect
      }
      goto(Disconnected)
  }
  whenUnhandled {
    case Event(CreateRandomNameChild(child), _) ⇒
      sender ! (try context.actorOf(child) catch { case e: Exception ⇒ e })
      stay()
  }

  initialize

  onTermination {
    case StopEvent(reason, state, stateData) ⇒
      log.debug("Successfully disposed")
      executorService.shutdown()
  }

  def shutdownCompleted(cause: ShutdownSignalException) {
    self ! cause
  }
}

private[amqp] object ConnectionConnected {
  def unapply(msg: Any) = msg match {
    case Transition(connection, _, Connected) ⇒ Some(connection)
    case CurrentState(connection, Connected)  ⇒ Some(connection)
    case _                                    ⇒ None
  }
}

private[amqp] object ConnectionDisconnected {
  def unapply(msg: Any) = msg match {
    case Transition(_, _, Disconnected) ⇒ true
    case CurrentState(_, Disconnected)  ⇒ true
    case _                              ⇒ false
  }
}

object ConnectionProperties {
  def apply(system: ActorSystem) = {
    val settings = AMQP(system)
    import settings._
    new ConnectionProperties(
      DefaultUser,
      DefaultPass,
      DefaultAddresses,
      DefaultVhost,
      DefaultAmqpHeartbeatMs milliseconds,
      DefaultMaxReconnectDelayMs milliseconds,
      DefaultChannelThreads,
      system)
  }
}
case class ConnectionProperties(user: String,
                                pass: String,
                                addresses: Seq[String],
                                vhost: String,
                                amqpHeartbeat: Duration,
                                maxReconnectDelay: Duration,
                                channelThreads: Int,
                                system: ActorSystem)

class DurableConnection(private[amqp] val connectionProperties: ConnectionProperties) {

  private[amqp] val durableConnectionActor = connectionProperties.system.actorOf(Props(new DurableConnectionActor(connectionProperties)), "connection")
  durableConnectionActor ! Connect

  def withConnection[T](callback: Connection ⇒ T): Future[T] = {
    import akka.pattern.ask
    (durableConnectionActor.ask(ConnectionCallback(callback))(Timeout(5000))).map(_.asInstanceOf[T])
  }

  def withTempChannel[T](callback: Channel ⇒ T): Future[T] = {
    withConnection { conn ⇒
      val ch = conn.createChannel()
      try {
        callback(ch)
      } finally {
        if (ch.isOpen) { ch.close() }
      }
    }
  }

  def newChannel() = {
    new DurableChannel(this)
  }

  def newPublisher(exchange: Exchange) = {
    new DurablePublisher(this, exchange)
  }

  def newStashingPublisher(exchange: Exchange) = {
    new DurablePublisher(this, exchange, true)
  }

  def newConfirmingPublisher(exchange: Exchange) = {
    new DurablePublisher(this, exchange) with ConfirmingPublisher
  }

  def newConsumer(queue: Queue, deliveryHandler: ActorRef, autoAcknowledge: Boolean, queueBindings: QueueBinding*): DurableConsumer = {
    new DurableConsumer(this, queue, deliveryHandler, autoAcknowledge, queueBindings: _*)
  }

  def disconnect() {
    durableConnectionActor ! Disconnect
  }

  def connect() {
    durableConnectionActor ! Connect
  }

  def dispose() {
    if (!durableConnectionActor.isTerminated) {
      durableConnectionActor ! Disconnect
      durableConnectionActor ! PoisonPill
    }
  }
}
