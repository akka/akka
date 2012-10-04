package akka.remote

import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.gracefulStop
import akka.remote.HeadActor.Listen
import akka.remote.HeadActor.Send
import akka.remote.transport.Transport.InboundAssociation
import akka.remote.transport._
import akka.util.Timeout
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit._
import scala.annotation.switch
import scala.collection.immutable.{Seq, HashMap}
import scala.concurrent.util.duration._
import scala.concurrent.util.{Duration, FiniteDuration}
import scala.concurrent.{Promise, Await, Future}
import scala.util.control.NonFatal

class RemotingConfig(config: Config) {

  import config._
  import scala.collection.JavaConverters._

  val ShutdownTimeout: FiniteDuration = Duration(getMilliseconds("akka.remoting.shutdown-timeout"), MILLISECONDS)

  val StartupTimeout: FiniteDuration = Duration(getMilliseconds("akka.remoting.startup-timeout"), MILLISECONDS)

  val RetryLatchClosedFor: Long = getMilliseconds("akka.remoting.retry-latch-closed-for")

  val UsePassiveConnections: Boolean = getBoolean("akka.remoting.use-passive-connections")

  val BackoffPeriod: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.backoff-interval"), MILLISECONDS)

  val Transports: Seq[(String, Config)] =
    config.getConfigList("akka.remoting.transports").asScala.map {
      conf => (conf.getString("transport-class"), conf.getConfig("settings"))
    }.toList
}

object Remoting {

  val HeadActorName = "remoteTransportHeadActor"

  def localAddressForRemote(transportMapping: Map[String, Set[(Transport, Address)]], remote: Address): Address = {

    transportMapping.get(remote.protocol) match {
      case Some(transports) =>
        val responsibleTransports = transports.filter(_._1.isResponsibleFor(remote))

        (responsibleTransports.size: @switch) match {
          case 0 =>
            throw new RemoteTransportException(
              s"No transport is responsible for address: ${remote} although protocol ${remote.protocol} is available." +
              " Make sure at least one transport is configured to be responsible for the address.",
              null)

          case 1 =>
            responsibleTransports.head._2

          case _ =>
            throw new RemoteTransportException(
              s"Multiple transports are available for ${remote}: ${responsibleTransports.mkString(",")}. " +
              "Remoting cannot decide which transport to use to reach the remote system. Change your configuration " +
              "so that only one transport is responsible for the address.",
              null)
        }
      case None => throw new RemoteTransportException(s"No transport is loaded for protocol: ${remote.protocol}", null)
    }
  }

}

class Remoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  @volatile private var headActor: ActorRef = _
  @volatile var transportMapping: Map[String, Set[(Transport, Address)]] = _
  @volatile var addresses: Set[Address] = _
  private val settings = new RemotingConfig(provider.remoteSettings.config)

  override def localAddressForRemote(remote: Address): Address = Remoting.localAddressForRemote(transportMapping, remote)

  val log: LoggingAdapter = Logging(system.eventStream, "Remoting")

  override def shutdown(): Unit = {
    if (headActor != null) {
      try {
        val stopped: Future[Boolean] = gracefulStop(headActor, 5 seconds)(system)

        if (Await.result(stopped, settings.ShutdownTimeout)) {
          log.info("Remoting stopped successfully")
        }

      } catch {
        case e: java.util.concurrent.TimeoutException ⇒ log.warning("Shutdown timed out.")
        case NonFatal(e)                         ⇒ log.error(e, "Shutdown failed.")
      } finally {
        headActor = null
      }
    }
  }

  // Start assumes that it cannot be followed by another start() without having a shutdown() first
  override def start(): Unit = {
    if (headActor eq null) {
      log.info("Starting remoting")
      headActor = system.asInstanceOf[ActorSystemImpl].systemActorOf(
        Props(new HeadActor(provider.remoteSettings.config, log)), Remoting.HeadActorName)

      implicit val timeout = new Timeout(settings.StartupTimeout)

      try {
        val addressesPromise: Promise[Set[(Transport, Address)]] = Promise()
        headActor ! Listen(addressesPromise)

        val transports: Set[(Transport, Address)] = Await.result(addressesPromise.future, timeout.duration)
        transportMapping = transports.groupBy { case (transport, _) => transport.schemeIdentifier }.mapValues {
          _.toSet
        }

        addresses = transports.map{_._2}.toSet

      } catch {
        case e: java.util.concurrent.TimeoutException ⇒ throw new RemoteTransportException("Startup timed out", e)
        case NonFatal(e)                         ⇒ throw new RemoteTransportException("Startup failed", e)
      }

    } else {
      log.warning("Remoting was already started. Ignoring start attempt.")
    }
  }

  // TODO: this is called in RemoteActorRefProvider to handle the lifecycle of connections (clients)
  // which is not how things work in the new remoting
  override def shutdownClientConnection(address: Address): Unit = {
    // Ignore
  }

  // TODO: this is never called anywhere, should be taken out from RemoteTransport API
  override def restartClientConnection(address: Address): Unit = {
    // Ignore
  }

  override def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    headActor ! Send(message, senderOption, recipient)
  }

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def useUntrustedMode: Boolean = provider.remoteSettings.UntrustedMode

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def logRemoteLifeCycleEvents: Boolean = provider.remoteSettings.LogRemoteLifeCycleEvents

}

private[remote] object HeadActor {

  sealed trait RemotingCommand
  case class Listen(addressesPromise: Promise[Set[(Transport, Address)]]) extends RemotingCommand

  case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand {
    override def toString = s"Remote message $senderOption -> $recipient"
  }

  sealed trait EndpointPolicy
  case class Pass(endpoint: ActorRef) extends EndpointPolicy
  case class Latched(timeOfFailure: Long) extends EndpointPolicy
  case class Quarantine(reason: String) extends EndpointPolicy

  case object Prune

  class EndpointRegistry {
    @volatile private var addressToEndpointAndPolicy = HashMap[Address, EndpointPolicy]()
    @volatile private var endpointToAddress = HashMap[ActorRef, Address]()

    def getEndpointWithPolicy(address: Address): Option[EndpointPolicy] = addressToEndpointAndPolicy.get(address)

    def prune(pruneAge: Long): Unit = {
      addressToEndpointAndPolicy = addressToEndpointAndPolicy.filter {
        case (_, Pass(_))               ⇒ true
        case (_, Latched(timeOfFailure)) ⇒ timeOfFailure + pruneAge > System.nanoTime()
      }
    }

    def registerEndpoint(address: Address, endpoint: ActorRef): ActorRef = {
      addressToEndpointAndPolicy = addressToEndpointAndPolicy + (address -> Pass(endpoint))
      endpointToAddress = endpointToAddress + (endpoint -> address)
      endpoint
    }

    def markFailed(endpoint: ActorRef, timeOfFailure: Long) {
      addressToEndpointAndPolicy += endpointToAddress(endpoint) -> Latched(timeOfFailure)
      endpointToAddress = endpointToAddress - endpoint
    }

    def removeIfNotLatched(endpoint: ActorRef): Unit =  {
      endpointToAddress.get(endpoint) foreach { address =>
        addressToEndpointAndPolicy.get(address) match {
          case Some(Pass(_)) ⇒
            addressToEndpointAndPolicy = addressToEndpointAndPolicy - address
            endpointToAddress = endpointToAddress - endpoint
          case _ ⇒
        }
      }

    }
  }
}

private[remote] class HeadActor(conf: Config, log: LoggingAdapter) extends Actor {

  import HeadActor._
  import context.dispatcher

  private val settings = new RemotingConfig(conf)
  private val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]


  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  private val endpoints = new EndpointRegistry
  private var transports: List[Transport] = List()
  // Mapping between transports and the local addresses they listen to
  private var transportMapping: Map[Address, Transport] = Map()

  private val retryLatchEnabled = settings.RetryLatchClosedFor > 0L
  private val pruneInterval: Long = if (retryLatchEnabled) settings.RetryLatchClosedFor * 2L else 0L
  private val pruneTimerCancellable: Option[Cancellable] = if (retryLatchEnabled) {
    Some(context.system.scheduler.schedule(pruneInterval milliseconds, pruneInterval milliseconds, self, Prune))
  } else {
    None
  }

  private def failureStrategy = if (!retryLatchEnabled) {
    // This strategy keeps all the messages in the stash of the endpoint so restart will transfer the queue
    // to the restarted endpoint -- thus no messages are lost
    Restart
  } else {
    // This strategy throws away all the messages enqueued in the endpoint (in its stash), registers the time of failure,
    // keeps throwing away messages until the retry latch becomes open (time specified in RetryLatchClosedFor)
    endpoints.markFailed(sender, System.nanoTime())
    Stop
  }

  override val supervisorStrategy = OneForOneStrategy() {
    case NonFatal(_)          ⇒ failureStrategy
  }

  override def preStart(): Unit = loadTransports()

  def receive = {
    case Listen(addressesPromise) ⇒
      initializeTransports(addressesPromise)

    case s @ Send(message, senderOption, recipientRef) ⇒
      val recipientAddress = recipientRef.path.address

      endpoints.getEndpointWithPolicy(recipientAddress) match {
        case Some(Pass(endpoint)) ⇒
          endpoint ! s

        case Some(Latched(timeOfFailure)) ⇒ if (retryLatchOpen(timeOfFailure)) {
          createEndpoint(recipientAddress, recipientRef.localAddressToUse, None) ! s
        } else {
          extendedSystem.deadLetters ! message
        }

        case None ⇒
          createEndpoint(recipientAddress, recipientRef.localAddressToUse, None) ! s

      }


    case InboundAssociation(handle) ⇒
      val endpoint = createEndpoint(handle.remoteAddress, handle.localAddress, Some(handle))
      if (settings.UsePassiveConnections) {
        endpoints.registerEndpoint(handle.localAddress, endpoint)
      }

    case Terminated(endpoint) ⇒
      endpoints.removeIfNotLatched(endpoint)

    case Prune ⇒
      endpoints.prune(settings.RetryLatchClosedFor)
  }

  private def loadTransports(): Unit = {

    transports = (for ((fqn, config) <- settings.Transports) yield {

      val args = Seq(classOf[ExtendedActorSystem] -> context.system, classOf[Config] -> config)

      val wrappedTransport = context.system.asInstanceOf[ActorSystemImpl].dynamicAccess
        .createInstanceFor[Transport](fqn, args).recover({

        case exception ⇒ throw new IllegalArgumentException(
          (s"Cannot instantiate transport [$fqn]. " +
            "Make sure it extends [akka.remote.transport.Transport] and has constructor with " +
            "[com.typesafe.config.Config] and [akka.actor.ExtendedActorSystem] parameters"), exception)

      }).get

      new AkkaProtocolTransport(wrappedTransport, context.system, new AkkaProtocolConfig(conf), AkkaPduProtobufCodec)

    }).toList

  }

  private def initializeTransports(addressesPromise: Promise[Set[(Transport, Address)]]): Unit = {
    val listens: Future[Seq[(Transport, (Address, Promise[ActorRef]))]] = Future.sequence(
      transports.map { transport => transport.listen.map { transport -> _ } }
    )

    listens.onSuccess {
      case results =>
        val transportsAndAddresses = (for ((transport, (address, promise)) <- results) yield {
          promise.success(self)
          transport -> address
        }).toSet
        addressesPromise.success(transportsAndAddresses)

        transportMapping = HashMap() ++ results.groupBy { case (_, (transportAddress, _)) => transportAddress }.map {
          case (a, t) =>
            if (t.size > 1) {
              throw new RemoteTransportException(s"There are more than one transports listening on local address $a", null)
            }

            a -> t.head._1
        }
    }

    listens.onFailure {
      case e: Throwable => addressesPromise.failure(e)
    }
  }

  private def createEndpoint(remoteAddress: Address,
                             localAddress: Address,
                             handleOption: Option[AssociationHandle]): ActorRef = {
    assert(transportMapping.contains(localAddress))

    val endpoint = context.actorOf(Props(
      new EndpointWriter(
        !handleOption.isDefined,
        handleOption,
        remoteAddress,
        transportMapping(localAddress),
        settings,
        AkkaPduProtobufCodec)
    ).withDispatcher("akka.remoting.writer-dispatcher"))

    endpoints.registerEndpoint(remoteAddress, endpoint)
  }

  private def retryLatchOpen(timeOfFailure: Long): Boolean = (timeOfFailure + settings.RetryLatchClosedFor) < System.nanoTime()

  override def postStop(): Unit = {
    pruneTimerCancellable.foreach { _.cancel() }
    transports foreach { transport =>
      try {
        transport.shutdown()
      } catch {
        case NonFatal(e) ⇒
          log.error(e, s"Unable to shut down the underlying Transport: $transport")
      }
    }
  }

}