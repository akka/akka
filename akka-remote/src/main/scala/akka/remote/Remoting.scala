package akka.remote

import scala.language.postfixOps
import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.event.{ Logging, LoggingAdapter }
import akka.pattern.gracefulStop
import akka.remote.EndpointManager.{ StartupFinished, ManagementCommand, Listen, Send }
import akka.remote.transport.Transport.{ AssociationEventListener, InboundAssociation }
import akka.remote.transport._
import akka.util.Timeout
import com.typesafe.config.{ ConfigFactory, Config }
import scala.collection.immutable.{ Seq, HashMap }
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Await, Future }
import scala.util.control.NonFatal
import java.net.URLEncoder
import java.util.concurrent.TimeoutException
import scala.util.{ Failure, Success }
import scala.collection.immutable
import akka.japi.Util.immutableSeq
import akka.remote.Remoting.{ TransportSupervisor, RegisterTransportActor }

class RemotingSettings(val config: Config) {

  import config._
  import scala.collection.JavaConverters._

  val LogLifecycleEvents: Boolean = getBoolean("akka.remoting.log-remote-lifecycle-events")

  val ShutdownTimeout: FiniteDuration = Duration(getMilliseconds("akka.remoting.shutdown-timeout"), MILLISECONDS)

  val StartupTimeout: FiniteDuration = Duration(getMilliseconds("akka.remoting.startup-timeout"), MILLISECONDS)

  val RetryGateClosedFor: Long = getNanoseconds("akka.remoting.retry-gate-closed-for")

  val UsePassiveConnections: Boolean = getBoolean("akka.remoting.use-passive-connections")

  val MaximumRetriesInWindow: Int = getInt("akka.remoting.maximum-retries-in-window")

  val RetryWindow: FiniteDuration = Duration(getMilliseconds("akka.remoting.retry-window"), MILLISECONDS)

  val BackoffPeriod: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.backoff-interval"), MILLISECONDS)

  val Transports: Seq[(String, Seq[String], Config)] = transportNames.map { name ⇒
    val transportConfig = transportConfigFor(name)
    (transportConfig.getString("transport-class"),
      immutableSeq(transportConfig.getStringList("applied-adapters")),
      transportConfig)
  }

  val Adapters: Map[String, String] = configToMap(getConfig("akka.remoting.adapters"))

  private def transportNames: Seq[String] = immutableSeq(getStringList("akka.remoting.enabled-transports"))

  private def transportConfigFor(transportName: String): Config = getConfig("akka.remoting.transports." + transportName)

  private def configToMap(cfg: Config): Map[String, String] =
    cfg.root.unwrapped.asScala.toMap.map { case (k, v) ⇒ (k, v.toString) }
}

private[remote] case class RARP(provider: RemoteActorRefProvider) extends Extension
private[remote] object RARP extends ExtensionId[RARP] with ExtensionIdProvider {

  override def lookup() = RARP

  override def createExtension(system: ExtendedActorSystem) = RARP(system.provider.asInstanceOf[RemoteActorRefProvider])
}

private[remote] object Remoting {

  final val EndpointManagerName = "endpointManager"

  def localAddressForRemote(transportMapping: Map[String, Set[(Transport, Address)]], remote: Address): Address = {

    transportMapping.get(remote.protocol) match {
      case Some(transports) ⇒
        val responsibleTransports = transports.filter { case (t, _) ⇒ t.isResponsibleFor(remote) }

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
              s"Multiple transports are available for [${remote}]: [${responsibleTransports.mkString(",")}]. " +
                "Remoting cannot decide which transport to use to reach the remote system. Change your configuration " +
                "so that only one transport is responsible for the address.",
              null)
        }
      case None ⇒ throw new RemoteTransportException(
        s"No transport is loaded for protocol: ${remote.protocol}, available protocols: ${transportMapping.keys.mkString}", null)
    }
  }

  case class RegisterTransportActor(props: Props, name: String)

  private[Remoting] class TransportSupervisor extends Actor {
    override def supervisorStrategy = OneForOneStrategy() {
      case NonFatal(e) ⇒ Restart
    }

    def receive = {
      case RegisterTransportActor(props, name) ⇒ sender ! context.actorOf(props, name)
    }
  }

}

private[remote] class Remoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  @volatile private var endpointManager: Option[ActorRef] = None
  @volatile private var transportMapping: Map[String, Set[(Transport, Address)]] = _
  // This is effectively a write-once variable similar to a lazy val. The reason for not using a lazy val is exception
  // handling.
  @volatile var addresses: Set[Address] = _
  // FIXME: Temporary workaround until next Pull Request as the means of configuration changed
  override def defaultAddress: Address = addresses.head

  private val settings = new RemotingSettings(provider.remoteSettings.config)

  val transportSupervisor = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props[TransportSupervisor], "transports")

  override def localAddressForRemote(remote: Address): Address = Remoting.localAddressForRemote(transportMapping, remote)

  val log: LoggingAdapter = Logging(system.eventStream, "Remoting")
  val eventPublisher = new EventPublisher(system, log, settings.LogLifecycleEvents)

  private def notifyError(msg: String, cause: Throwable): Unit =
    eventPublisher.notifyListeners(RemotingErrorEvent(new RemoteTransportException(msg, cause)))

  override def shutdown(): Unit = {
    endpointManager match {
      case Some(manager) ⇒
        try {
          val stopped: Future[Boolean] = gracefulStop(manager, settings.ShutdownTimeout)(system)

          if (Await.result(stopped, settings.ShutdownTimeout)) {
            eventPublisher.notifyListeners(RemotingShutdownEvent)
          }

        } catch {
          case e: TimeoutException ⇒ notifyError("Shutdown timed out.", e)
          case NonFatal(e)         ⇒ notifyError("Shutdown failed.", e)
        } finally endpointManager = None
      case None ⇒ log.warning("Remoting is not running. Ignoring shutdown attempt.")
    }
  }

  // Start assumes that it cannot be followed by another start() without having a shutdown() first
  override def start(): Unit = {
    endpointManager match {
      case None ⇒
        log.info("Starting remoting")
        val manager: ActorRef = system.asInstanceOf[ActorSystemImpl].systemActorOf(
          Props(new EndpointManager(provider.remoteSettings.config, log)), Remoting.EndpointManagerName)
        endpointManager = Some(manager)

        implicit val timeout = new Timeout(settings.StartupTimeout)

        try {
          val addressesPromise: Promise[Set[(Transport, Address)]] = Promise()
          manager ! Listen(addressesPromise)

          val transports: Set[(Transport, Address)] = Await.result(addressesPromise.future, timeout.duration)
          transportMapping = transports.groupBy { case (transport, _) ⇒ transport.schemeIdentifier }.mapValues {
            _.toSet
          }

          addresses = transports.map { _._2 }.toSet

          manager ! StartupFinished
          eventPublisher.notifyListeners(RemotingListenEvent(addresses))

        } catch {
          case e: TimeoutException ⇒ notifyError("Startup timed out", e)
          case NonFatal(e)         ⇒ notifyError("Startup failed", e)
        }

      case Some(_) ⇒
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

  override def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = endpointManager match {
    case Some(manager) ⇒ manager.tell(Send(message, senderOption, recipient), sender = senderOption getOrElse Actor.noSender)
    case None          ⇒ throw new IllegalStateException("Attempted to send remote message but Remoting is not running.")
  }

  override def managementCommand(cmd: Any): Future[Boolean] = endpointManager match {
    case Some(manager) ⇒
      val statusPromise = Promise[Boolean]()
      manager.tell(ManagementCommand(cmd, statusPromise), sender = Actor.noSender)
      statusPromise.future
    case None ⇒ throw new IllegalStateException("Attempted to send management command but Remoting is not running.")
  }

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def useUntrustedMode: Boolean = provider.remoteSettings.UntrustedMode

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def logRemoteLifeCycleEvents: Boolean = provider.remoteSettings.LogRemoteLifeCycleEvents

}

private[remote] object EndpointManager {

  sealed trait RemotingCommand
  case class Listen(addressesPromise: Promise[Set[(Transport, Address)]]) extends RemotingCommand
  case object StartupFinished extends RemotingCommand

  case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand {
    override def toString = s"Remote message $senderOption -> $recipient"
  }

  case class ManagementCommand(cmd: Any, statusPromise: Promise[Boolean]) extends RemotingCommand

  sealed trait EndpointPolicy
  case class Pass(endpoint: ActorRef) extends EndpointPolicy
  case class Gated(timeOfFailure: Long) extends EndpointPolicy
  case class Quarantined(reason: Throwable) extends EndpointPolicy

  case object Prune

  // Not threadsafe -- only to be used in HeadActor
  private[EndpointManager] class EndpointRegistry {
    private var addressToEndpointAndPolicy = HashMap[Address, EndpointPolicy]()
    private var endpointToAddress = HashMap[ActorRef, Address]()
    private var addressToPassive = HashMap[Address, ActorRef]()

    def getEndpointWithPolicy(address: Address): Option[EndpointPolicy] = addressToEndpointAndPolicy.get(address)

    def hasActiveEndpointFor(address: Address): Boolean = addressToEndpointAndPolicy.get(address) match {
      case Some(Pass(_)) ⇒ true
      case _             ⇒ false
    }

    def passiveEndpointFor(address: Address): Option[ActorRef] = addressToPassive.get(address)

    def isQuarantined(address: Address): Boolean = addressToEndpointAndPolicy.get(address) match {
      case Some(Quarantined(_)) ⇒ true
      case _                    ⇒ false
    }

    def prune(pruneAge: Long): Unit = {
      addressToEndpointAndPolicy = addressToEndpointAndPolicy.filter {
        case (_, Gated(timeOfFailure)) ⇒ timeOfFailure + pruneAge > System.nanoTime()
        case _                         ⇒ true
      }
    }

    def registerActiveEndpoint(address: Address, endpoint: ActorRef): ActorRef = {
      addressToEndpointAndPolicy = addressToEndpointAndPolicy + (address -> Pass(endpoint))
      endpointToAddress = endpointToAddress + (endpoint -> address)
      endpoint
    }

    def registerPassiveEndpoint(address: Address, endpoint: ActorRef): ActorRef = {
      addressToPassive = addressToPassive + (address -> endpoint)
      endpointToAddress = endpointToAddress + (endpoint -> address)
      endpoint
    }

    // FIXME: Temporary hack to verify the bug
    def isPassive(endpoint: ActorRef): Boolean = addressToPassive.contains(endpointToAddress(endpoint))

    def markFailed(endpoint: ActorRef, timeOfFailure: Long): Unit = {
      addressToEndpointAndPolicy += endpointToAddress(endpoint) -> Gated(timeOfFailure)
      if (!isPassive(endpoint)) endpointToAddress = endpointToAddress - endpoint
    }

    def markQuarantine(address: Address, reason: Throwable): Unit =
      addressToEndpointAndPolicy += address -> Quarantined(reason)

    def removeIfNotGated(endpoint: ActorRef): Unit = {
      endpointToAddress.get(endpoint) foreach { address ⇒
        addressToEndpointAndPolicy.get(address) foreach { 
          case Pass(_) ⇒ addressToEndpointAndPolicy = addressToEndpointAndPolicy - address
          case _       ⇒
        }

        endpointToAddress = endpointToAddress - endpoint
        addressToPassive = addressToPassive - address
      }
    }
  }
}

private[remote] class EndpointManager(conf: Config, log: LoggingAdapter) extends Actor {

  import EndpointManager._
  import context.dispatcher

  val settings = new RemotingSettings(conf)
  val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val endpointId: Iterator[Int] = Iterator from 0

  val eventPublisher = new EventPublisher(context.system, log, settings.LogLifecycleEvents)

  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  val endpoints = new EndpointRegistry
  // Mapping between transports and the local addresses they listen to
  var transportMapping: Map[Address, Transport] = Map()

  def retryGateEnabled = settings.RetryGateClosedFor > 0L
  val pruneInterval: Long = if (retryGateEnabled) settings.RetryGateClosedFor * 2L else 0L
  val pruneTimerCancellable: Option[Cancellable] = if (retryGateEnabled)
    Some(context.system.scheduler.schedule(pruneInterval milliseconds, pruneInterval milliseconds, self, Prune))
  else None

  override val supervisorStrategy = OneForOneStrategy(settings.MaximumRetriesInWindow, settings.RetryWindow) {
    case InvalidAssociation(localAddress, remoteAddress, e) ⇒
      endpoints.markQuarantine(remoteAddress, e)
      Stop

    case NonFatal(e) ⇒
      if (!retryGateEnabled)
        // This strategy keeps all the messages in the stash of the endpoint so restart will transfer the queue
        // to the restarted endpoint -- thus no messages are lost
        Restart
      else {
        // This strategy throws away all the messages enqueued in the endpoint (in its stash), registers the time of failure,
        // keeps throwing away messages until the retry gate becomes open (time specified in RetryGateClosedFor)
        endpoints.markFailed(sender, System.nanoTime())
        Stop
      }
  }

  def receive = {
    case Listen(addressesPromise) ⇒

      try initializeTransports(addressesPromise) catch {
        case NonFatal(e) ⇒
          addressesPromise.failure(e)
          context.stop(self)
      }

    case ManagementCommand(_, statusPromise) ⇒ statusPromise.success(false)

    case StartupFinished                     ⇒ context.become(accepting)
  }

  val accepting: Receive = {
    case ManagementCommand(cmd, statusPromise) ⇒
      transportMapping.values foreach { _.managementCommand(cmd, statusPromise) }

    case s @ Send(message, senderOption, recipientRef) ⇒
      val recipientAddress = recipientRef.path.address

      endpoints.getEndpointWithPolicy(recipientAddress) match {
        case Some(Pass(endpoint)) ⇒ endpoint ! s
        case Some(Gated(timeOfFailure)) ⇒ if (retryGateOpen(timeOfFailure)) {
          val endpoint = createEndpoint(
            recipientAddress,
            recipientRef.localAddressToUse,
            transportMapping(recipientRef.localAddressToUse),
            settings,
            None)
          endpoints.registerActiveEndpoint(recipientAddress, endpoint)
          endpoint ! s
        } else extendedSystem.deadLetters forward message
        case Some(Quarantined(_)) ⇒ extendedSystem.deadLetters forward message
        case None ⇒
          val endpoint = createEndpoint(
            recipientAddress,
            recipientRef.localAddressToUse,
            transportMapping(recipientRef.localAddressToUse),
            settings,
            None)
          endpoints.registerActiveEndpoint(recipientAddress, endpoint)
          endpoint ! s

      }

    case InboundAssociation(handle) ⇒ endpoints.passiveEndpointFor(handle.remoteAddress) match {
      case Some(endpoint) ⇒ endpoint ! EndpointWriter.TakeOver(handle)
      case None ⇒
        val endpoint = createEndpoint(
          handle.remoteAddress,
          handle.localAddress,
          transportMapping(handle.localAddress),
          settings,
          Some(handle))
        eventPublisher.notifyListeners(AssociatedEvent(handle.localAddress, handle.remoteAddress, true))
        if (settings.UsePassiveConnections && !endpoints.hasActiveEndpointFor(handle.remoteAddress)) {
          endpoints.registerActiveEndpoint(handle.remoteAddress, endpoint)
        } else if (!endpoints.isQuarantined(handle.remoteAddress))
          endpoints.registerPassiveEndpoint(handle.remoteAddress, endpoint)
        else handle.disassociate()
    }
    case Terminated(endpoint) ⇒ endpoints.removeIfNotGated(endpoint)
    case Prune                ⇒ endpoints.prune(settings.RetryGateClosedFor)
  }

  private def initializeTransports(addressesPromise: Promise[Set[(Transport, Address)]]): Unit = {
    val transports = for ((fqn, adapters, config) ← settings.Transports) yield {

      val args = Seq(classOf[ExtendedActorSystem] -> context.system, classOf[Config] -> config)

      val driver = extendedSystem.dynamicAccess
        .createInstanceFor[Transport](fqn, args).recover({

          case exception ⇒ throw new IllegalArgumentException(
            (s"Cannot instantiate transport [$fqn]. " +
              "Make sure it extends [akka.remote.transport.Transport] and has constructor with " +
              "[akka.actor.ExtendedActorSystem] and [com.typesafe.config.Config] parameters"), exception)

        }).get

      val wrappedTransport =
        adapters.map { TransportAdaptersExtension.get(context.system).getAdapterProvider(_) }.foldLeft(driver) {
          (t: Transport, provider: TransportAdapterProvider) ⇒
            provider(t, context.system.asInstanceOf[ExtendedActorSystem])
        }

      new AkkaProtocolTransport(wrappedTransport, context.system, new AkkaProtocolSettings(conf), AkkaPduProtobufCodec)
    }

    val listens: Future[Seq[(Transport, (Address, Promise[AssociationEventListener]))]] = Future.sequence(
      transports.map { transport ⇒ transport.listen map (transport -> _) })

    listens.onComplete {
      case Success(results) ⇒
        transportMapping = results.groupBy {
          case (_, (transportAddress, _)) ⇒ transportAddress
        } map {
          case (a, t) if t.size > 1 ⇒
            throw new RemoteTransportException(s"There are more than one transports listening on local address [$a]", null)
          case (a, t) ⇒ a -> t.head._1
        }

        val transportsAndAddresses = (for ((transport, (address, promise)) ← results) yield {
          promise.success(self)
          transport -> address
        }).toSet
        addressesPromise.success(transportsAndAddresses)

      case Failure(reason) ⇒ addressesPromise.failure(reason)
    }
  }

  private def createEndpoint(remoteAddress: Address,
                             localAddress: Address,
                             transport: Transport,
                             endpointSettings: RemotingSettings,
                             handleOption: Option[AssociationHandle]): ActorRef = {
    assert(transportMapping contains localAddress)

    context.watch(context.actorOf(Props(
      new EndpointWriter(
        handleOption,
        localAddress,
        remoteAddress,
        transport,
        endpointSettings,
        AkkaPduProtobufCodec))
      .withDispatcher("akka.remoting.writer-dispatcher"),
      "endpointWriter-" + URLEncoder.encode(remoteAddress.toString, "utf-8") + "-" + endpointId.next()))

    context.watch(endpoint)

  }

  private def retryGateOpen(timeOfFailure: Long): Boolean = (timeOfFailure + settings.RetryGateClosedFor) < System.nanoTime()

  override def postStop(): Unit = {
    pruneTimerCancellable.foreach { _.cancel() }
    transportMapping.values foreach { transport ⇒
      try transport.shutdown() catch {
        case NonFatal(e) ⇒
          log.error(e, s"Unable to shut down the underlying transport: [$transport]")
      }
    }
  }

}