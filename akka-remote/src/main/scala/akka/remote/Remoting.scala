package akka.remote

import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.event.{ Logging, LoggingAdapter }
import akka.pattern.gracefulStop
import akka.remote.EndpointManager.Listen
import akka.remote.EndpointManager.Send
import akka.remote.transport.Transport.InboundAssociation
import akka.remote.transport._
import akka.util.Timeout
import com.typesafe.config.Config
import scala.collection.immutable.{ Seq, HashMap }
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Await, Future }
import scala.util.control.NonFatal
import java.net.URLEncoder
import java.util.concurrent.TimeoutException
import scala.util.{ Failure, Success }

class RemotingSettings(config: Config) {

  import config._
  import scala.collection.JavaConverters._

  val LogLifecycleEvents: Boolean = getBoolean("akka.remoting.log-remote-lifecycle-events")

  val ShutdownTimeout: FiniteDuration = Duration(getMilliseconds("akka.remoting.shutdown-timeout"), MILLISECONDS)

  val StartupTimeout: FiniteDuration = Duration(getMilliseconds("akka.remoting.startup-timeout"), MILLISECONDS)

  val RetryLatchClosedFor: Long = getMilliseconds("akka.remoting.retry-latch-closed-for")

  val UsePassiveConnections: Boolean = getBoolean("akka.remoting.use-passive-connections")

  val MaximumRetriesInWindow: Int = getInt("akka.remoting.maximum-retries-in-window")

  val RetryWindow: FiniteDuration = Duration(getMilliseconds("akka.remoting.retry-window"), MILLISECONDS)

  val BackoffPeriod: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.backoff-interval"), MILLISECONDS)

  val Transports: List[(String, Config)] =
    config.getConfigList("akka.remoting.transports").asScala.map {
      conf ⇒ (conf.getString("transport-class"), conf.getConfig("settings"))
    }.toList
}

private[remote] object Remoting {

  val EndpointManagerName = "remoteTransportHeadActor"

  def localAddressForRemote(transportMapping: Map[String, Set[(Transport, Address)]], remote: Address): Address = {

    transportMapping.get(remote.protocol) match {
      case Some(transports) ⇒
        val responsibleTransports = transports.filter(_._1.isResponsibleFor(remote))

        responsibleTransports.size match {
          case 0 ⇒
            throw new RemoteTransportException(
              s"No transport is responsible for address: ${remote} although protocol ${remote.protocol} is available." +
                " Make sure at least one transport is configured to be responsible for the address.",
              null)

          case 1 ⇒
            responsibleTransports.head._2

          case _ ⇒
            throw new RemoteTransportException(
              s"Multiple transports are available for ${remote}: ${responsibleTransports.mkString(",")}. " +
                "Remoting cannot decide which transport to use to reach the remote system. Change your configuration " +
                "so that only one transport is responsible for the address.",
              null)
        }
      case None ⇒ throw new RemoteTransportException(s"No transport is loaded for protocol: ${remote.protocol}", null)
    }
  }

}

private[remote] class Remoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  @volatile private var endpointManager: ActorRef = _
  @volatile var transportMapping: Map[String, Set[(Transport, Address)]] = _
  @volatile var addresses: Set[Address] = _
  private val settings = new RemotingSettings(provider.remoteSettings.config)

  override def localAddressForRemote(remote: Address): Address = Remoting.localAddressForRemote(transportMapping, remote)

  val log: LoggingAdapter = Logging(system.eventStream, "Remoting")
  val eventPublisher = new EventPublisher(system, log, settings.LogLifecycleEvents)

  private def notifyError(msg: String, cause: Throwable): Unit =
    eventPublisher.notifyListeners(RemotingErrorEvent(new RemoteTransportException(msg, cause)))

  override def shutdown(): Unit = {
    if (endpointManager != null) {
      try {
        val stopped: Future[Boolean] = gracefulStop(endpointManager, settings.ShutdownTimeout)(system)

        if (Await.result(stopped, settings.ShutdownTimeout)) {
          eventPublisher.notifyListeners(RemotingShutdownEvent)
        }

      } catch {
        case e: TimeoutException ⇒ notifyError("Shutdown timed out.", e)
        case NonFatal(e)         ⇒ notifyError("Shutdown failed.", e)
      } finally {
        endpointManager = null
      }
    }
  }

  // Start assumes that it cannot be followed by another start() without having a shutdown() first
  override def start(): Unit = {
    if (endpointManager eq null) {
      log.info("Starting remoting")
      endpointManager = system.asInstanceOf[ActorSystemImpl].systemActorOf(
        Props(new EndpointManager(provider.remoteSettings.config, log)), Remoting.EndpointManagerName)

      implicit val timeout = new Timeout(settings.StartupTimeout)

      try {
        val addressesPromise: Promise[Set[(Transport, Address)]] = Promise()
        endpointManager ! Listen(addressesPromise)

        val transports: Set[(Transport, Address)] = Await.result(addressesPromise.future, timeout.duration)
        transportMapping = transports.groupBy { case (transport, _) ⇒ transport.schemeIdentifier }.mapValues {
          _.toSet
        }

        addresses = transports.map { _._2 }.toSet
        eventPublisher.notifyListeners(RemotingListenEvent(addresses))

      } catch {
        case e: TimeoutException ⇒ notifyError("Startup timed out", e)
        case NonFatal(e)         ⇒ notifyError("Startup failed", e)
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
    endpointManager.tell(Send(message, senderOption, recipient), sender = Actor.noSender)
  }

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def useUntrustedMode: Boolean = provider.remoteSettings.UntrustedMode

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def logRemoteLifeCycleEvents: Boolean = provider.remoteSettings.LogRemoteLifeCycleEvents

}

private[remote] object EndpointManager {

  sealed trait RemotingCommand
  case class Listen(addressesPromise: Promise[Set[(Transport, Address)]]) extends RemotingCommand

  case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand {
    override def toString = s"Remote message $senderOption -> $recipient"
  }

  sealed trait EndpointPolicy
  case class Pass(endpoint: ActorRef) extends EndpointPolicy
  case class Latched(timeOfFailure: Long) extends EndpointPolicy
  case class Quarantined(reason: Throwable) extends EndpointPolicy

  case object Prune

  // Not threadsafe -- only to be used in HeadActor
  private[EndpointManager] class EndpointRegistry {
    @volatile private var addressToEndpointAndPolicy = HashMap[Address, EndpointPolicy]()
    @volatile private var endpointToAddress = HashMap[ActorRef, Address]()

    def getEndpointWithPolicy(address: Address): Option[EndpointPolicy] = addressToEndpointAndPolicy.get(address)

    def prune(pruneAge: Long): Unit = {
      addressToEndpointAndPolicy = addressToEndpointAndPolicy.filter {
        case (_, Pass(_))                ⇒ true
        case (_, Latched(timeOfFailure)) ⇒ timeOfFailure + pruneAge > System.nanoTime()
      }
    }

    def registerEndpoint(address: Address, endpoint: ActorRef): ActorRef = {
      addressToEndpointAndPolicy = addressToEndpointAndPolicy + (address -> Pass(endpoint))
      endpointToAddress = endpointToAddress + (endpoint -> address)
      endpoint
    }

    def markFailed(endpoint: ActorRef, timeOfFailure: Long): Unit = {
      addressToEndpointAndPolicy += endpointToAddress(endpoint) -> Latched(timeOfFailure)
      endpointToAddress = endpointToAddress - endpoint
    }

    def markQuarantine(address: Address, reason: Throwable): Unit =
      addressToEndpointAndPolicy += address -> Quarantined(reason)

    def removeIfNotLatched(endpoint: ActorRef): Unit = {
      endpointToAddress.get(endpoint) foreach { address ⇒
        addressToEndpointAndPolicy.get(address) foreach { policy ⇒
          policy match {
            case Pass(_) ⇒
              addressToEndpointAndPolicy = addressToEndpointAndPolicy - address
              endpointToAddress = endpointToAddress - endpoint
            case _ ⇒
          }
        }
      }
    }
  }
}

private[remote] class EndpointManager(conf: Config, log: LoggingAdapter) extends Actor {

  import EndpointManager._
  import context.dispatcher

  val settings = new RemotingSettings(conf)
  val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]
  var endpointId: Long = 0L

  val eventPublisher = new EventPublisher(context.system, log, settings.LogLifecycleEvents)

  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  val endpoints = new EndpointRegistry
  // Mapping between transports and the local addresses they listen to
  var transportMapping: Map[Address, Transport] = Map()

  val retryLatchEnabled = settings.RetryLatchClosedFor > 0L
  val pruneInterval: Long = if (retryLatchEnabled) settings.RetryLatchClosedFor * 2L else 0L
  val pruneTimerCancellable: Option[Cancellable] = if (retryLatchEnabled)
    Some(context.system.scheduler.schedule(pruneInterval milliseconds, pruneInterval milliseconds, self, Prune))
  else None

  override val supervisorStrategy = OneForOneStrategy(settings.MaximumRetriesInWindow, settings.RetryWindow) {
    case InvalidAssociation(localAddress, remoteAddress, e) ⇒
      endpoints.markQuarantine(remoteAddress, e)
      Stop

    case NonFatal(e) ⇒
      if (!retryLatchEnabled)
        // This strategy keeps all the messages in the stash of the endpoint so restart will transfer the queue
        // to the restarted endpoint -- thus no messages are lost
        Restart
      else {
        // This strategy throws away all the messages enqueued in the endpoint (in its stash), registers the time of failure,
        // keeps throwing away messages until the retry latch becomes open (time specified in RetryLatchClosedFor)
        endpoints.markFailed(sender, System.nanoTime())
        Stop
      }
  }

  def receive = {
    case Listen(addressesPromise) ⇒ try initializeTransports(addressesPromise) catch {
      case NonFatal(e) ⇒
        addressesPromise.failure(e)
        context.stop(self)
    }
  }

  val accepting: Receive = {
    case s @ Send(message, senderOption, recipientRef) ⇒
      val recipientAddress = recipientRef.path.address

      endpoints.getEndpointWithPolicy(recipientAddress) match {
        case Some(Pass(endpoint)) ⇒ endpoint ! s
        case Some(Latched(timeOfFailure)) ⇒ if (retryLatchOpen(timeOfFailure))
          createEndpoint(recipientAddress, recipientRef.localAddressToUse, None) ! s
        else extendedSystem.deadLetters ! message
        case Some(Quarantined(_)) ⇒ extendedSystem.deadLetters ! message
        case None                 ⇒ createEndpoint(recipientAddress, recipientRef.localAddressToUse, None) ! s

      }

    case InboundAssociation(handle) ⇒
      val endpoint = createEndpoint(handle.remoteAddress, handle.localAddress, Some(handle))
      eventPublisher.notifyListeners(AssociatedEvent(handle.localAddress, handle.remoteAddress, true))
      if (settings.UsePassiveConnections) endpoints.registerEndpoint(handle.localAddress, endpoint)
    case Terminated(endpoint) ⇒ endpoints.removeIfNotLatched(endpoint)
    case Prune                ⇒ endpoints.prune(settings.RetryLatchClosedFor)
  }

  private def initializeTransports(addressesPromise: Promise[Set[(Transport, Address)]]): Unit = {
    val transports = for ((fqn, config) ← settings.Transports) yield {

      val args = Seq(classOf[ExtendedActorSystem] -> context.system, classOf[Config] -> config)

      val wrappedTransport = context.system.asInstanceOf[ActorSystemImpl].dynamicAccess
        .createInstanceFor[Transport](fqn, args).recover({

          case exception ⇒ throw new IllegalArgumentException(
            (s"Cannot instantiate transport [$fqn]. " +
              "Make sure it extends [akka.remote.transport.Transport] and has constructor with " +
              "[akka.actor.ExtendedActorSystem] and [com.typesafe.config.Config] parameters"), exception)

        }).get

      new AkkaProtocolTransport(wrappedTransport, context.system, new AkkaProtocolSettings(conf), AkkaPduProtobufCodec)

    }

    val listens: Future[Seq[(Transport, (Address, Promise[ActorRef]))]] = Future.sequence(
      transports.map { transport ⇒ transport.listen.map { transport -> _ } })

    listens.onComplete {
      case Success(results) ⇒
        val transportsAndAddresses = (for ((transport, (address, promise)) ← results) yield {
          promise.success(self)
          transport -> address
        }).toSet
        addressesPromise.success(transportsAndAddresses)

        context.become(accepting)

        transportMapping = HashMap() ++ results.groupBy { case (_, (transportAddress, _)) ⇒ transportAddress }.map {
          case (a, t) ⇒
            if (t.size > 1)
              throw new RemoteTransportException(s"There are more than one transports listening on local address $a", null)

            a -> t.head._1
        }

      case Failure(reason) ⇒ addressesPromise.failure(reason)
    }
  }

  private def createEndpoint(remoteAddress: Address,
                             localAddress: Address,
                             handleOption: Option[AssociationHandle]): ActorRef = {
    assert(transportMapping.contains(localAddress))
    val id = endpointId
    endpointId += 1L

    val endpoint = context.actorOf(Props(
      new EndpointWriter(
        handleOption,
        localAddress,
        remoteAddress,
        transportMapping(localAddress),
        settings,
        AkkaPduProtobufCodec))
      .withDispatcher("akka.remoting.writer-dispatcher"),
      "endpointWriter-" + URLEncoder.encode(remoteAddress.toString, "utf-8") + "-" + endpointId)

    endpoints.registerEndpoint(remoteAddress, endpoint)
  }

  private def retryLatchOpen(timeOfFailure: Long): Boolean = (timeOfFailure + settings.RetryLatchClosedFor) < System.nanoTime()

  override def postStop(): Unit = {
    pruneTimerCancellable.foreach { _.cancel() }
    transportMapping.values foreach { transport ⇒
      try transport.shutdown()
      catch {
        case NonFatal(e) ⇒
          log.error(e, s"Unable to shut down the underlying Transport: $transport")
      }
    }
  }

}