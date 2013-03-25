/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.event.{ Logging, LoggingAdapter }
import akka.japi.Util.immutableSeq
import akka.pattern.{ gracefulStop, pipe, ask }
import akka.remote.EndpointManager._
import akka.remote.Remoting.TransportSupervisor
import akka.remote.transport.Transport.{ ActorAssociationEventListener, AssociationEventListener, InboundAssociation }
import akka.remote.transport._
import akka.util.Timeout
import com.typesafe.config.Config
import java.net.URLEncoder
import java.util.concurrent.TimeoutException
import scala.collection.immutable.{ Seq, HashMap }
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Await, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

/**
 * INTERNAL API
 */
private[remote] object AddressUrlEncoder {
  def apply(address: Address): String = URLEncoder.encode(address.toString, "utf-8")
}

/**
 * INTERNAL API
 */
private[remote] case class RARP(provider: RemoteActorRefProvider) extends Extension
/**
 * INTERNAL API
 */
private[remote] object RARP extends ExtensionId[RARP] with ExtensionIdProvider {

  override def lookup() = RARP

  override def createExtension(system: ExtendedActorSystem) = RARP(system.provider.asInstanceOf[RemoteActorRefProvider])
}

/**
 * INTERNAL API
 */
private[remote] object Remoting {

  final val EndpointManagerName = "endpointManager"

  def localAddressForRemote(transportMapping: Map[String, Set[(Transport, Address)]], remote: Address): Address = {

    transportMapping.get(remote.protocol) match {
      case Some(transports) ⇒
        val responsibleTransports = transports.filter { case (t, _) ⇒ t.isResponsibleFor(remote) }

        responsibleTransports.size match {
          case 0 ⇒
            throw new RemoteTransportException(
              s"No transport is responsible for address: [$remote] although protocol [${remote.protocol}] is available." +
                " Make sure at least one transport is configured to be responsible for the address.",
              null)

          case 1 ⇒
            responsibleTransports.head._2

          case _ ⇒
            throw new RemoteTransportException(
              s"Multiple transports are available for [$remote]: [${responsibleTransports.mkString(",")}]. " +
                "Remoting cannot decide which transport to use to reach the remote system. Change your configuration " +
                "so that only one transport is responsible for the address.",
              null)
        }
      case None ⇒ throw new RemoteTransportException(
        s"No transport is loaded for protocol: [${remote.protocol}], available protocols: [${transportMapping.keys.mkString(", ")}]", null)
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

/**
 * INTERNAL API
 */
private[remote] class Remoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  @volatile private var endpointManager: Option[ActorRef] = None
  @volatile private var transportMapping: Map[String, Set[(Transport, Address)]] = _
  // This is effectively a write-once variable similar to a lazy val. The reason for not using a lazy val is exception
  // handling.
  @volatile var addresses: Set[Address] = _
  // This variable has the same semantics as the addresses variable, in the sense it is written once, and emulates
  // a lazy val
  @volatile var defaultAddress: Address = _

  import provider.remoteSettings._

  val transportSupervisor = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props[TransportSupervisor], "transports")

  override def localAddressForRemote(remote: Address): Address = Remoting.localAddressForRemote(transportMapping, remote)

  val log: LoggingAdapter = Logging(system.eventStream, "Remoting")
  val eventPublisher = new EventPublisher(system, log, LogRemoteLifecycleEvents)

  private def notifyError(msg: String, cause: Throwable): Unit =
    eventPublisher.notifyListeners(RemotingErrorEvent(new RemoteTransportException(msg, cause)))

  override def shutdown(): Future[Unit] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    endpointManager match {
      case Some(manager) ⇒
        implicit val timeout = ShutdownTimeout
        val stopped: Future[Boolean] = (manager ? ShutdownAndFlush).mapTo[Boolean]

        def finalize(): Unit = {
          eventPublisher.notifyListeners(RemotingShutdownEvent)
          endpointManager = None
        }

        stopped.onComplete {
          case Success(flushSuccessful) ⇒
            if (!flushSuccessful)
              log.warning("Shutdown finished, but flushing timed out. Some messages might not have been sent. " +
                "Increase akka.remote.flush-wait-on-shutdown to a larger value to avoid this.")
            finalize()

          case Failure(e) ⇒
            notifyError("Failure during shutdown of remoting.", e)
            finalize()
        }

        stopped map { _ ⇒ () } // RARP needs only type Unit, not a boolean
      case None ⇒
        log.warning("Remoting is not running. Ignoring shutdown attempt.")
        Future successful (())
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

        try {
          val addressesPromise: Promise[Seq[(Transport, Address)]] = Promise()
          manager ! Listen(addressesPromise)

          val transports: Seq[(Transport, Address)] = Await.result(addressesPromise.future,
            StartupTimeout.duration)
          if (transports.isEmpty) throw new RemoteTransportException("No transport drivers were loaded.", null)

          transportMapping = transports.groupBy {
            case (transport, _) ⇒ transport.schemeIdentifier
          } map { case (k, v) ⇒ k -> v.toSet }

          defaultAddress = transports.head._2
          addresses = transports.map { _._2 }.toSet

          log.info("Remoting started; listening on addresses :" + addresses.mkString("[", ", ", "]"))

          manager ! StartupFinished
          eventPublisher.notifyListeners(RemotingListenEvent(addresses))

        } catch {
          case e: TimeoutException ⇒
            notifyError("Startup timed out", e)
            throw e
          case NonFatal(e) ⇒
            notifyError("Startup failed", e)
            throw e
        }

      case Some(_) ⇒
        log.warning("Remoting was already started. Ignoring start attempt.")
    }
  }

  override def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = endpointManager match {
    case Some(manager) ⇒ manager.tell(Send(message, senderOption, recipient), sender = senderOption getOrElse Actor.noSender)
    case None          ⇒ throw new IllegalStateException("Attempted to send remote message but Remoting is not running.")
  }

  override def managementCommand(cmd: Any): Future[Boolean] = endpointManager match {
    case Some(manager) ⇒
      import system.dispatcher
      implicit val timeout = CommandAckTimeout
      manager ? ManagementCommand(cmd) map { case ManagementCommandAck(status) ⇒ status }
    case None ⇒ throw new IllegalStateException("Attempted to send management command but Remoting is not running.")
  }

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def useUntrustedMode: Boolean = provider.remoteSettings.UntrustedMode

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def logRemoteLifeCycleEvents: Boolean = LogRemoteLifecycleEvents

}

/**
 * INTERNAL API
 */
private[remote] object EndpointManager {

  // Messages between Remoting and EndpointManager
  sealed trait RemotingCommand
  case class Listen(addressesPromise: Promise[Seq[(Transport, Address)]]) extends RemotingCommand
  case object StartupFinished extends RemotingCommand
  case object ShutdownAndFlush extends RemotingCommand
  case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand {
    override def toString = s"Remote message $senderOption -> $recipient"
  }
  case class ManagementCommand(cmd: Any) extends RemotingCommand
  case class ManagementCommandAck(status: Boolean)

  // Messages internal to EndpointManager
  case object Prune
  case class ListensResult(addressesPromise: Promise[Seq[(Transport, Address)]],
                           results: Seq[(Transport, Address, Promise[AssociationEventListener])])

  sealed trait EndpointPolicy {

    /**
     * Indicates that the policy does not contain an active endpoint, but it is a tombstone of a previous failure
     */
    def isTombstone: Boolean
  }
  case class Pass(endpoint: ActorRef) extends EndpointPolicy {
    override def isTombstone: Boolean = false
  }
  case class Gated(timeOfRelease: Deadline) extends EndpointPolicy {
    override def isTombstone: Boolean = true
  }
  case class Quarantined(reason: Throwable) extends EndpointPolicy {
    override def isTombstone: Boolean = true
  }

  // Not threadsafe -- only to be used in HeadActor
  class EndpointRegistry {
    private var addressToWritable = HashMap[Address, EndpointPolicy]()
    private var writableToAddress = HashMap[ActorRef, Address]()
    private var addressToReadonly = HashMap[Address, ActorRef]()
    private var readonlyToAddress = HashMap[ActorRef, Address]()

    def registerWritableEndpoint(address: Address, endpoint: ActorRef): ActorRef = addressToWritable.get(address) match {
      case Some(Pass(e)) ⇒
        throw new IllegalArgumentException(s"Attempting to overwrite existing endpoint [$e] with [$endpoint]")
      case _ ⇒
        addressToWritable += address -> Pass(endpoint)
        writableToAddress += endpoint -> address
        endpoint
    }

    def registerReadOnlyEndpoint(address: Address, endpoint: ActorRef): ActorRef = {
      addressToReadonly += address -> endpoint
      readonlyToAddress += endpoint -> address
      endpoint
    }

    def unregisterEndpoint(endpoint: ActorRef): Unit =
      if (isWritable(endpoint)) {
        val address = writableToAddress(endpoint)
        addressToWritable.get(address) match {
          case Some(policy) if policy.isTombstone ⇒ // There is already a tombstone directive, leave it there
          case _                                  ⇒ addressToWritable -= address
        }
        writableToAddress -= endpoint
      } else if (isReadOnly(endpoint)) {
        addressToReadonly -= readonlyToAddress(endpoint)
        readonlyToAddress -= endpoint
      }

    def writableEndpointWithPolicyFor(address: Address): Option[EndpointPolicy] = addressToWritable.get(address)

    def hasWritableEndpointFor(address: Address): Boolean = writableEndpointWithPolicyFor(address) match {
      case Some(Pass(_)) ⇒ true
      case _             ⇒ false
    }

    def readOnlyEndpointFor(address: Address): Option[ActorRef] = addressToReadonly.get(address)

    def isWritable(endpoint: ActorRef): Boolean = writableToAddress contains endpoint

    def isReadOnly(endpoint: ActorRef): Boolean = readonlyToAddress contains endpoint

    def isQuarantined(address: Address): Boolean = writableEndpointWithPolicyFor(address) match {
      case Some(Quarantined(_)) ⇒ true
      case _                    ⇒ false
    }

    def markAsFailed(endpoint: ActorRef, timeOfRelease: Deadline): Unit =
      if (isWritable(endpoint)) {
        addressToWritable += writableToAddress(endpoint) -> Gated(timeOfRelease)
        writableToAddress -= endpoint
      } else if (isReadOnly(endpoint)) {
        addressToReadonly -= readonlyToAddress(endpoint)
        readonlyToAddress -= endpoint
      }

    def markAsQuarantined(address: Address, reason: Throwable): Unit = addressToWritable += address -> Quarantined(reason)

    def allEndpoints: collection.Iterable[ActorRef] = writableToAddress.keys ++ readonlyToAddress.keys

    def pruneGatedEntries(): Unit = {
      addressToWritable = addressToWritable.filter {
        case (_, Gated(timeOfRelease)) ⇒ timeOfRelease.hasTimeLeft
        case _                         ⇒ true
      }
    }
  }
}

/**
 * INTERNAL API
 */
private[remote] class EndpointManager(conf: Config, log: LoggingAdapter) extends Actor {

  import EndpointManager._
  import context.dispatcher

  val settings = new RemoteSettings(conf)
  val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val endpointId: Iterator[Int] = Iterator from 0

  val eventPublisher = new EventPublisher(context.system, log, settings.LogRemoteLifecycleEvents)

  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  val endpoints = new EndpointRegistry
  // Mapping between transports and the local addresses they listen to
  var transportMapping: Map[Address, Transport] = Map()

  def retryGateEnabled = settings.RetryGateClosedFor > Duration.Zero
  val pruneInterval: FiniteDuration = if (retryGateEnabled) settings.RetryGateClosedFor * 2 else Duration.Zero
  val pruneTimerCancellable: Option[Cancellable] = if (retryGateEnabled)
    Some(context.system.scheduler.schedule(pruneInterval, pruneInterval, self, Prune))
  else None

  override val supervisorStrategy =
    OneForOneStrategy(settings.MaximumRetriesInWindow, settings.RetryWindow, loggingEnabled = false) {
      case InvalidAssociation(localAddress, remoteAddress, _) ⇒
        log.error("Tried to associate with invalid remote address [{}]. " +
          "Address is now quarantined, all messages to this address will be delivered to dead letters.", remoteAddress)
        endpoints.markAsFailed(sender, Deadline.now + settings.UnknownAddressGateClosedFor)
        Stop

      case NonFatal(e) ⇒

        // logging
        e match {
          case _: EndpointDisassociatedException | _: EndpointAssociationException ⇒ // no logging
          case _ ⇒ log.error(e, e.getMessage)
        }

        // Retrying immediately if the retry gate is disabled, and it is an endpoint used for writing.
        if (!retryGateEnabled && endpoints.isWritable(sender)) {
          // This strategy keeps all the messages in the stash of the endpoint so restart will transfer the queue
          // to the restarted endpoint -- thus no messages are lost
          Restart
        } else {
          // This strategy throws away all the messages enqueued in the endpoint (in its stash), registers the time of failure,
          // keeps throwing away messages until the retry gate becomes open (time specified in RetryGateClosedFor)
          endpoints.markAsFailed(sender, Deadline.now + settings.RetryGateClosedFor)
          Stop
        }
    }

  def receive = {
    case Listen(addressesPromise) ⇒
      listens map { ListensResult(addressesPromise, _) } pipeTo self
    case ListensResult(addressesPromise, results) ⇒
      transportMapping = results.groupBy {
        case (_, transportAddress, _) ⇒ transportAddress
      } map {
        case (a, t) if t.size > 1 ⇒
          throw new RemoteTransportException(s"There are more than one transports listening on local address [$a]", null)
        case (a, t) ⇒ a -> t.head._1
      }
      // Register to each transport as listener and collect mapping to addresses
      val transportsAndAddresses = results map {
        case (transport, address, promise) ⇒
          promise.success(ActorAssociationEventListener(self))
          transport -> address
      }
      addressesPromise.success(transportsAndAddresses)
    case ManagementCommand(_) ⇒
      sender ! ManagementCommandAck(false)
    case StartupFinished ⇒
      context.become(accepting)
    case ShutdownAndFlush ⇒
      sender ! true
      context.stop(self) // Nothing to flush at this point
  }

  val accepting: Receive = {
    case ManagementCommand(cmd) ⇒
      val allStatuses = transportMapping.values map { transport ⇒
        transport.managementCommand(cmd)
      }
      Future.fold(allStatuses)(true)(_ && _) map ManagementCommandAck pipeTo sender

    case s @ Send(message, senderOption, recipientRef) ⇒
      val recipientAddress = recipientRef.path.address

      def createAndRegisterWritingEndpoint(): ActorRef = endpoints.registerWritableEndpoint(recipientAddress, createEndpoint(
        recipientAddress,
        recipientRef.localAddressToUse,
        transportMapping(recipientRef.localAddressToUse),
        settings,
        None))

      endpoints.writableEndpointWithPolicyFor(recipientAddress) match {
        case Some(Pass(endpoint)) ⇒
          endpoint ! s
        case Some(Gated(timeOfRelease)) ⇒
          if (timeOfRelease.isOverdue()) createAndRegisterWritingEndpoint() ! s
          else forwardToDeadLetters(s)
        case Some(Quarantined(_)) ⇒
          forwardToDeadLetters(s)
        case None ⇒
          createAndRegisterWritingEndpoint() ! s

      }

    case InboundAssociation(handle) ⇒ endpoints.readOnlyEndpointFor(handle.remoteAddress) match {
      case Some(endpoint) ⇒ endpoint ! EndpointWriter.TakeOver(handle)
      case None ⇒
        if (endpoints.isQuarantined(handle.remoteAddress)) handle.disassociate()
        else {
          eventPublisher.notifyListeners(AssociatedEvent(handle.localAddress, handle.remoteAddress, true))
          val endpoint = createEndpoint(
            handle.remoteAddress,
            handle.localAddress,
            transportMapping(handle.localAddress),
            settings,
            Some(handle))
          if (settings.UsePassiveConnections && !endpoints.hasWritableEndpointFor(handle.remoteAddress))
            endpoints.registerWritableEndpoint(handle.remoteAddress, endpoint)
          else
            endpoints.registerReadOnlyEndpoint(handle.remoteAddress, endpoint)
        }
    }
    case Terminated(endpoint) ⇒
      endpoints.unregisterEndpoint(endpoint)
    case Prune ⇒
      endpoints.pruneGatedEntries()
    case ShutdownAndFlush ⇒
      // Shutdown all endpoints and signal to sender when ready (and whether all endpoints were shut down gracefully)
      val sys = context.system // Avoid closing over context
      Future sequence endpoints.allEndpoints.map {
        gracefulStop(_, settings.FlushWait, EndpointWriter.FlushAndStop)(sys)
      } map { _.foldLeft(true) { _ && _ } } pipeTo sender
      // Ignore all other writes
      context.become(flushing)
  }

  def flushing: Receive = {
    case s: Send               ⇒ forwardToDeadLetters(s)
    case InboundAssociation(h) ⇒ h.disassociate()
    case Terminated(_)         ⇒ // why should we care now?
  }

  private def forwardToDeadLetters(s: Send): Unit = {
    val sender = s.senderOption.getOrElse(extendedSystem.deadLetters)
    extendedSystem.deadLetters.tell(DeadLetter(s.message, sender, s.recipient), sender)
  }

  private def listens: Future[Seq[(Transport, Address, Promise[AssociationEventListener])]] = {
    /*
     * Constructs chains of adapters on top of each driver as given in configuration. The resulting structure looks
     * like the following:
     *   AkkaProtocolTransport <- Adapter <- ... <- Adapter <- Driver
     *
     * The transports variable contains only the heads of each chains (the AkkaProtocolTransport instances).
     */
    val transports: Seq[AkkaProtocolTransport] = for ((fqn, adapters, config) ← settings.Transports) yield {

      val args = Seq(classOf[ExtendedActorSystem] -> context.system, classOf[Config] -> config)

      // Loads the driver -- the bottom element of the chain.
      // The chain at this point:
      //   Driver
      val driver = extendedSystem.dynamicAccess
        .createInstanceFor[Transport](fqn, args).recover({

          case exception ⇒ throw new IllegalArgumentException(
            (s"Cannot instantiate transport [$fqn]. " +
              "Make sure it extends [akka.remote.transport.Transport] and has constructor with " +
              "[akka.actor.ExtendedActorSystem] and [com.typesafe.config.Config] parameters"), exception)

        }).get

      // Iteratively decorates the bottom level driver with a list of adapters.
      // The chain at this point:
      //   Adapter <- ... <- Adapter <- Driver
      val wrappedTransport =
        adapters.map { TransportAdaptersExtension.get(context.system).getAdapterProvider(_) }.foldLeft(driver) {
          (t: Transport, provider: TransportAdapterProvider) ⇒
            // The TransportAdapterProvider will wrap the given Transport and returns with a wrapped one
            provider.create(t, context.system.asInstanceOf[ExtendedActorSystem])
        }

      // Apply AkkaProtocolTransport wrapper to the end of the chain
      // The chain at this point:
      //   AkkaProtocolTransport <- Adapter <- ... <- Adapter <- Driver
      new AkkaProtocolTransport(wrappedTransport, context.system, new AkkaProtocolSettings(conf), AkkaPduProtobufCodec)
    }

    // Collect all transports, listen addresses and listener promises in one future
    Future.sequence(transports.map { transport ⇒
      transport.listen map { case (address, listenerPromise) ⇒ (transport, address, listenerPromise) }
    })
  }

  private def createEndpoint(remoteAddress: Address,
                             localAddress: Address,
                             transport: Transport,
                             endpointSettings: RemoteSettings,
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
      .withDispatcher("akka.remote.writer-dispatcher"),
      "endpointWriter-" + AddressUrlEncoder(remoteAddress) + "-" + endpointId.next()))
  }

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
