/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.event.{ Logging, LoggingAdapter }
import akka.pattern.{ gracefulStop, pipe, ask }
import akka.remote.EndpointManager._
import akka.remote.Remoting.TransportSupervisor
import akka.remote.transport.Transport.{ ActorAssociationEventListener, AssociationEventListener, InboundAssociation }
import akka.remote.transport._
import com.typesafe.config.Config
import java.net.URLEncoder
import java.util.concurrent.TimeoutException
import scala.collection.immutable.{ Seq, HashMap }
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Await, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }
import akka.remote.transport.AkkaPduCodec.Message
import java.util.concurrent.ConcurrentHashMap
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }

/**
 * INTERNAL API
 */
private[remote] object AddressUrlEncoder {
  def apply(address: Address): String = URLEncoder.encode(address.toString, "utf-8")
}

/**
 * INTERNAL API
 */
private[remote] case class RARP(provider: RemoteActorRefProvider) extends Extension {
  def configureDispatcher(props: Props): Props = provider.remoteSettings.configureDispatcher(props)
}
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

  def localAddressForRemote(transportMapping: Map[String, Set[(AkkaProtocolTransport, Address)]], remote: Address): Address = {

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

  case class RegisterTransportActor(props: Props, name: String) extends NoSerializationVerificationNeeded

  private[Remoting] class TransportSupervisor extends Actor with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
    override def supervisorStrategy = OneForOneStrategy() {
      case NonFatal(e) ⇒ Restart
    }

    def receive = {
      case RegisterTransportActor(props, name) ⇒
        sender() ! context.actorOf(
          RARP(context.system).configureDispatcher(props.withDeploy(Deploy.local)),
          name)
    }
  }

}

/**
 * INTERNAL API
 */
private[remote] class Remoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  @volatile private var endpointManager: Option[ActorRef] = None
  @volatile private var transportMapping: Map[String, Set[(AkkaProtocolTransport, Address)]] = _
  // This is effectively a write-once variable similar to a lazy val. The reason for not using a lazy val is exception
  // handling.
  @volatile var addresses: Set[Address] = _
  // This variable has the same semantics as the addresses variable, in the sense it is written once, and emulates
  // a lazy val
  @volatile var defaultAddress: Address = _

  import provider.remoteSettings._

  val transportSupervisor = system.asInstanceOf[ActorSystemImpl].systemActorOf(
    configureDispatcher(Props[TransportSupervisor]),
    "transports")

  override def localAddressForRemote(remote: Address): Address = Remoting.localAddressForRemote(transportMapping, remote)

  val log: LoggingAdapter = Logging(system.eventStream, "Remoting")
  val eventPublisher = new EventPublisher(system, log, RemoteLifecycleEventsLogLevel)

  private def notifyError(msg: String, cause: Throwable): Unit =
    eventPublisher.notifyListeners(RemotingErrorEvent(new RemoteTransportException(msg, cause)))

  override def shutdown(): Future[Unit] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    endpointManager match {
      case Some(manager) ⇒
        implicit val timeout = ShutdownTimeout

        def finalize(): Unit = {
          eventPublisher.notifyListeners(RemotingShutdownEvent)
          endpointManager = None
        }

        (manager ? ShutdownAndFlush).mapTo[Boolean].andThen {
          case Success(flushSuccessful) ⇒
            if (!flushSuccessful)
              log.warning("Shutdown finished, but flushing might not have been successful and some messages might have been dropped. " +
                "Increase akka.remote.flush-wait-on-shutdown to a larger value to avoid this.")
            finalize()

          case Failure(e) ⇒
            notifyError("Failure during shutdown of remoting.", e)
            finalize()
        } map { _ ⇒ () } // RARP needs only type Unit, not a boolean
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
          configureDispatcher(Props(classOf[EndpointManager], provider.remoteSettings.config, log)).withDeploy(Deploy.local),
          Remoting.EndpointManagerName)
        endpointManager = Some(manager)

        try {
          val addressesPromise: Promise[Seq[(AkkaProtocolTransport, Address)]] = Promise()
          manager ! Listen(addressesPromise)

          val transports: Seq[(AkkaProtocolTransport, Address)] = Await.result(addressesPromise.future,
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
    case None          ⇒ throw new RemoteTransportExceptionNoStackTrace("Attempted to send remote message but Remoting is not running.", null)
  }

  override def managementCommand(cmd: Any): Future[Boolean] = endpointManager match {
    case Some(manager) ⇒
      import system.dispatcher
      implicit val timeout = CommandAckTimeout
      manager ? ManagementCommand(cmd) map { case ManagementCommandAck(status) ⇒ status }
    case None ⇒ throw new RemoteTransportExceptionNoStackTrace("Attempted to send management command but Remoting is not running.", null)
  }

  override def quarantine(remoteAddress: Address, uid: Option[Int]): Unit = endpointManager match {
    case Some(manager) ⇒ manager ! Quarantine(remoteAddress, uid)
    case _ ⇒ throw new RemoteTransportExceptionNoStackTrace(
      s"Attempted to quarantine address [$remoteAddress] with uid [$uid] but Remoting is not running", null)
  }

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  protected def useUntrustedMode: Boolean = provider.remoteSettings.UntrustedMode

  // Not used anywhere only to keep compatibility with RemoteTransport interface
  @deprecated("Use the LogRemoteLifecycleEvents setting instead.", "2.3")
  protected def logRemoteLifeCycleEvents: Boolean = LogRemoteLifecycleEvents

}

/**
 * INTERNAL API
 */
private[remote] object EndpointManager {

  // Messages between Remoting and EndpointManager
  sealed trait RemotingCommand extends NoSerializationVerificationNeeded
  case class Listen(addressesPromise: Promise[Seq[(AkkaProtocolTransport, Address)]]) extends RemotingCommand
  case object StartupFinished extends RemotingCommand
  case object ShutdownAndFlush extends RemotingCommand
  case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef, seqOpt: Option[SeqNo] = None)
    extends RemotingCommand with HasSequenceNumber {
    override def toString = s"Remote message $senderOption -> $recipient"

    // This MUST throw an exception to indicate that we attempted to put a nonsequenced message in one of the
    // acknowledged delivery buffers
    def seq = seqOpt.get
  }
  case class Quarantine(remoteAddress: Address, uid: Option[Int]) extends RemotingCommand
  case class ManagementCommand(cmd: Any) extends RemotingCommand
  case class ManagementCommandAck(status: Boolean)

  // Messages internal to EndpointManager
  case object Prune extends NoSerializationVerificationNeeded
  case class ListensResult(addressesPromise: Promise[Seq[(AkkaProtocolTransport, Address)]],
                           results: Seq[(AkkaProtocolTransport, Address, Promise[AssociationEventListener])])
    extends NoSerializationVerificationNeeded
  case class ListensFailure(addressesPromise: Promise[Seq[(AkkaProtocolTransport, Address)]], cause: Throwable)
    extends NoSerializationVerificationNeeded

  // Helper class to store address pairs
  case class Link(localAddress: Address, remoteAddress: Address)

  case class ResendState(uid: Int, buffer: AckedReceiveBuffer[Message])

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
  case class Quarantined(uid: Int, timeOfRelease: Deadline) extends EndpointPolicy {
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

    def isQuarantined(address: Address, uid: Int): Boolean = writableEndpointWithPolicyFor(address) match {
      case Some(Quarantined(`uid`, timeOfRelease)) ⇒ timeOfRelease.hasTimeLeft()
      case _                                       ⇒ false
    }

    /**
     * Marking an endpoint as failed means that we will not try to connect to the remote system within
     * the gated period but it is ok for the remote system to try to connect to us.
     */
    def markAsFailed(endpoint: ActorRef, timeOfRelease: Deadline): Unit =
      if (isWritable(endpoint)) {
        addressToWritable += writableToAddress(endpoint) -> Gated(timeOfRelease)
        writableToAddress -= endpoint
      } else if (isReadOnly(endpoint)) {
        addressToReadonly -= readonlyToAddress(endpoint)
        readonlyToAddress -= endpoint
      }

    def markAsQuarantined(address: Address, uid: Int, timeOfRelease: Deadline): Unit =
      addressToWritable += address -> Quarantined(uid, timeOfRelease)

    def removePolicy(address: Address): Unit =
      addressToWritable -= address

    def allEndpoints: collection.Iterable[ActorRef] = writableToAddress.keys ++ readonlyToAddress.keys

    def prune(): Unit = {
      addressToWritable = addressToWritable.filter {
        case (_, Gated(timeOfRelease))          ⇒ timeOfRelease.hasTimeLeft
        case (_, Quarantined(_, timeOfRelease)) ⇒ timeOfRelease.hasTimeLeft
        case _                                  ⇒ true
      }
    }
  }
}

/**
 * INTERNAL API
 */
private[remote] class EndpointManager(conf: Config, log: LoggingAdapter) extends Actor
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import EndpointManager._
  import context.dispatcher

  val settings = new RemoteSettings(conf)
  val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val endpointId: Iterator[Int] = Iterator from 0

  val eventPublisher = new EventPublisher(context.system, log, settings.RemoteLifecycleEventsLogLevel)

  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  val endpoints = new EndpointRegistry
  // Mapping between transports and the local addresses they listen to
  var transportMapping: Map[Address, AkkaProtocolTransport] = Map()

  def retryGateEnabled = settings.RetryGateClosedFor > Duration.Zero
  val pruneInterval: FiniteDuration = if (retryGateEnabled) settings.RetryGateClosedFor * 2 else Duration.Zero
  val pruneTimerCancellable: Option[Cancellable] = if (retryGateEnabled)
    Some(context.system.scheduler.schedule(pruneInterval, pruneInterval, self, Prune))
  else None

  var pendingReadHandoffs = Map[ActorRef, AkkaProtocolHandle]()

  override val supervisorStrategy =
    OneForOneStrategy(loggingEnabled = false) {
      case e @ InvalidAssociation(localAddress, remoteAddress, reason) ⇒
        log.warning("Tried to associate with unreachable remote address [{}]. " +
          "Address is now gated for {} ms, all messages to this address will be delivered to dead letters. Reason: {}",
          remoteAddress, settings.RetryGateClosedFor.toMillis, reason.getMessage)
        endpoints.markAsFailed(sender(), Deadline.now + settings.RetryGateClosedFor)
        context.system.eventStream.publish(AddressTerminated(remoteAddress))
        Stop

      case ShutDownAssociation(localAddress, remoteAddress, _) ⇒
        log.debug("Remote system with address [{}] has shut down. " +
          "Address is now gated for {} ms, all messages to this address will be delivered to dead letters.",
          remoteAddress, settings.RetryGateClosedFor.toMillis)
        endpoints.markAsFailed(sender(), Deadline.now + settings.RetryGateClosedFor)
        context.system.eventStream.publish(AddressTerminated(remoteAddress))
        Stop

      case HopelessAssociation(localAddress, remoteAddress, Some(uid), _) ⇒
        settings.QuarantineDuration match {
          case d: FiniteDuration ⇒
            endpoints.markAsQuarantined(remoteAddress, uid, Deadline.now + d)
            eventPublisher.notifyListeners(QuarantinedEvent(remoteAddress, uid))
          case _ ⇒ // disabled
        }
        context.system.eventStream.publish(AddressTerminated(remoteAddress))
        Stop

      case HopelessAssociation(localAddress, remoteAddress, None, _) ⇒
        log.warning("Association to [{}] with unknown UID is irrecoverably failed. " +
          "Address cannot be quarantined without knowing the UID, gating instead for {} ms.",
          remoteAddress, settings.RetryGateClosedFor.toMillis)
        endpoints.markAsFailed(sender(), Deadline.now + settings.RetryGateClosedFor)
        context.system.eventStream.publish(AddressTerminated(remoteAddress))
        Stop

      case NonFatal(e) ⇒
        // logging
        e match {
          case _: EndpointDisassociatedException | _: EndpointAssociationException ⇒ // no logging
          case _ ⇒ log.error(e, e.getMessage)
        }
        Stop
    }

  // Structure for saving reliable delivery state across restarts of Endpoints
  val receiveBuffers = new ConcurrentHashMap[Link, ResendState]()

  def receive = {
    case Listen(addressesPromise) ⇒
      listens map { ListensResult(addressesPromise, _) } recover {
        case NonFatal(e) ⇒ ListensFailure(addressesPromise, e)
      } pipeTo self
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
    case ListensFailure(addressesPromise, cause) ⇒
      addressesPromise.failure(cause)
    case ia: InboundAssociation ⇒
      context.system.scheduler.scheduleOnce(10.milliseconds, self, ia)
    case ManagementCommand(_) ⇒
      sender() ! ManagementCommandAck(status = false)
    case StartupFinished ⇒
      context.become(accepting)
    case ShutdownAndFlush ⇒
      sender() ! true
      context.stop(self) // Nothing to flush at this point
  }

  val accepting: Receive = {
    case ManagementCommand(cmd) ⇒
      val allStatuses = transportMapping.values map { transport ⇒
        transport.managementCommand(cmd)
      }
      Future.fold(allStatuses)(true)(_ && _) map ManagementCommandAck pipeTo sender()

    case Quarantine(address, uidOption) ⇒
      // Stop writers
      endpoints.writableEndpointWithPolicyFor(address) match {
        case Some(Pass(endpoint)) ⇒
          context.stop(endpoint)
          if (uidOption.isEmpty) {
            log.warning("Association to [{}] with unknown UID is reported as quarantined, but " +
              "address cannot be quarantined without knowing the UID, gating instead for {} ms.",
              address, settings.RetryGateClosedFor.toMillis)
            endpoints.markAsFailed(endpoint, Deadline.now + settings.RetryGateClosedFor)
          }
        case _ ⇒ // nothing to stop
      }
      // Stop inbound read-only associations
      endpoints.readOnlyEndpointFor(address) match {
        case Some(endpoint) ⇒ context.stop(endpoint)
        case _              ⇒ // nothing to stop
      }
      uidOption foreach { uid ⇒
        endpoints.markAsQuarantined(address, uid, Deadline.now + settings.QuarantineDuration)
        eventPublisher.notifyListeners(QuarantinedEvent(address, uid))
      }

    case s @ Send(message, senderOption, recipientRef, _) ⇒
      val recipientAddress = recipientRef.path.address

      def createAndRegisterWritingEndpoint(refuseUid: Option[Int]): ActorRef =
        endpoints.registerWritableEndpoint(
          recipientAddress,
          createEndpoint(
            recipientAddress,
            recipientRef.localAddressToUse,
            transportMapping(recipientRef.localAddressToUse),
            settings,
            handleOption = None,
            writing = true,
            refuseUid))

      endpoints.writableEndpointWithPolicyFor(recipientAddress) match {
        case Some(Pass(endpoint)) ⇒
          endpoint ! s
        case Some(Gated(timeOfRelease)) ⇒
          if (timeOfRelease.isOverdue()) createAndRegisterWritingEndpoint(refuseUid = None) ! s
          else extendedSystem.deadLetters ! s
        case Some(Quarantined(uid, _)) ⇒
          // timeOfRelease is only used for garbage collection reasons, therefore it is ignored here. We still have
          // the Quarantined tombstone and we know what UID we don't want to accept, so use it.
          createAndRegisterWritingEndpoint(refuseUid = Some(uid)) ! s
        case None ⇒
          createAndRegisterWritingEndpoint(refuseUid = None) ! s

      }

    case InboundAssociation(handle: AkkaProtocolHandle) ⇒ endpoints.readOnlyEndpointFor(handle.remoteAddress) match {
      case Some(endpoint) ⇒
        pendingReadHandoffs.get(endpoint) foreach (_.disassociate())
        pendingReadHandoffs += endpoint -> handle
        endpoint ! EndpointWriter.TakeOver(handle)
      case None ⇒
        if (endpoints.isQuarantined(handle.remoteAddress, handle.handshakeInfo.uid))
          handle.disassociate(AssociationHandle.Quarantined)
        else endpoints.writableEndpointWithPolicyFor(handle.remoteAddress) match {
          case Some(Pass(ep)) ⇒
            pendingReadHandoffs.get(ep) foreach (_.disassociate())
            pendingReadHandoffs += ep -> handle
            ep ! EndpointWriter.StopReading(ep)
          case _ ⇒
            val writing = settings.UsePassiveConnections && !endpoints.hasWritableEndpointFor(handle.remoteAddress)
            eventPublisher.notifyListeners(AssociatedEvent(handle.localAddress, handle.remoteAddress, inbound = true))
            val endpoint = createEndpoint(
              handle.remoteAddress,
              handle.localAddress,
              transportMapping(handle.localAddress),
              settings,
              Some(handle),
              writing,
              refuseUid = None)
            if (writing)
              endpoints.registerWritableEndpoint(handle.remoteAddress, endpoint)
            else {
              endpoints.registerReadOnlyEndpoint(handle.remoteAddress, endpoint)
              endpoints.writableEndpointWithPolicyFor(handle.remoteAddress) match {
                case Some(Pass(_)) ⇒ // Leave it alone
                case _ ⇒
                  // Since we just communicated with the guy we can lift gate, quarantine, etc. New writer will be
                  // opened at first write.
                  endpoints.removePolicy(handle.remoteAddress)
              }
            }
        }
    }
    case EndpointWriter.StoppedReading(endpoint) ⇒
      acceptPendingReader(takingOverFrom = endpoint)
    case Terminated(endpoint) ⇒
      acceptPendingReader(takingOverFrom = endpoint)
      endpoints.unregisterEndpoint(endpoint)
    case EndpointWriter.TookOver(endpoint, handle) ⇒
      removePendingReader(takingOverFrom = endpoint, withHandle = handle)
    case Prune ⇒
      endpoints.prune()
    case ShutdownAndFlush ⇒
      // Shutdown all endpoints and signal to sender() when ready (and whether all endpoints were shut down gracefully)

      def shutdownAll[T](resources: TraversableOnce[T])(shutdown: T ⇒ Future[Boolean]): Future[Boolean] = {
        (Future sequence resources.map(shutdown)) map { _.forall(identity) } recover {
          case NonFatal(_) ⇒ false
        }
      }

      (for {
        // The construction of the future for shutdownStatus has to happen after the flushStatus future has been finished
        // so that endpoints are shut down before transports.
        flushStatus ← shutdownAll(endpoints.allEndpoints)(gracefulStop(_, settings.FlushWait, EndpointWriter.FlushAndStop))
        shutdownStatus ← shutdownAll(transportMapping.values)(_.shutdown())
      } yield flushStatus && shutdownStatus) pipeTo sender()

      pendingReadHandoffs.valuesIterator foreach (_.disassociate(AssociationHandle.Shutdown))

      // Ignore all other writes
      context.become(flushing)
  }

  def flushing: Receive = {
    case s: Send                                   ⇒ extendedSystem.deadLetters ! s
    case InboundAssociation(h: AkkaProtocolHandle) ⇒ h.disassociate(AssociationHandle.Shutdown)
    case Terminated(_)                             ⇒ // why should we care now?
  }

  private def listens: Future[Seq[(AkkaProtocolTransport, Address, Promise[AssociationEventListener])]] = {
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
            s"Cannot instantiate transport [$fqn]. " +
              "Make sure it extends [akka.remote.transport.Transport] and has constructor with " +
              "[akka.actor.ExtendedActorSystem] and [com.typesafe.config.Config] parameters", exception)

        }).get

      // Iteratively decorates the bottom level driver with a list of adapters.
      // The chain at this point:
      //   Adapter <- ... <- Adapter <- Driver
      val wrappedTransport =
        adapters.map { TransportAdaptersExtension.get(context.system).getAdapterProvider }.foldLeft(driver) {
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

  private def acceptPendingReader(takingOverFrom: ActorRef): Unit = {
    if (pendingReadHandoffs.contains(takingOverFrom)) {
      val handle = pendingReadHandoffs(takingOverFrom)
      pendingReadHandoffs -= takingOverFrom
      eventPublisher.notifyListeners(AssociatedEvent(handle.localAddress, handle.remoteAddress, inbound = true))
      val endpoint = createEndpoint(
        handle.remoteAddress,
        handle.localAddress,
        transportMapping(handle.localAddress),
        settings,
        Some(handle),
        writing = false,
        refuseUid = None)
      endpoints.registerReadOnlyEndpoint(handle.remoteAddress, endpoint)
    }
  }

  private def removePendingReader(takingOverFrom: ActorRef, withHandle: AkkaProtocolHandle): Unit = {
    if (pendingReadHandoffs.get(takingOverFrom).exists(handle ⇒ handle == withHandle))
      pendingReadHandoffs -= takingOverFrom
  }

  private def createEndpoint(remoteAddress: Address,
                             localAddress: Address,
                             transport: AkkaProtocolTransport,
                             endpointSettings: RemoteSettings,
                             handleOption: Option[AkkaProtocolHandle],
                             writing: Boolean,
                             refuseUid: Option[Int]): ActorRef = {
    assert(transportMapping contains localAddress)
    assert(writing || refuseUid.isEmpty)

    if (writing) context.watch(context.actorOf(RARP(extendedSystem).configureDispatcher(ReliableDeliverySupervisor.props(
      handleOption,
      localAddress,
      remoteAddress,
      refuseUid,
      transport,
      endpointSettings,
      AkkaPduProtobufCodec,
      receiveBuffers)).withDeploy(Deploy.local),
      "reliableEndpointWriter-" + AddressUrlEncoder(remoteAddress) + "-" + endpointId.next()))
    else context.watch(context.actorOf(RARP(extendedSystem).configureDispatcher(EndpointWriter.props(
      handleOption,
      localAddress,
      remoteAddress,
      refuseUid,
      transport,
      endpointSettings,
      AkkaPduProtobufCodec,
      receiveBuffers,
      reliableDeliverySupervisor = None)).withDeploy(Deploy.local),
      "endpointWriter-" + AddressUrlEncoder(remoteAddress) + "-" + endpointId.next()))
  }

  override def postStop(): Unit = {
    pruneTimerCancellable.foreach { _.cancel() }
  }

}
