package akka.remote.transport

import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.gracefulStop
import akka.remote.transport.HeadActor.{Send, Listen}
import akka.remote.transport.Transport.InboundAssociation
import akka.remote.{RemoteActorRef, RemoteTransportException, RemoteTransport, RemoteActorRefProvider}
import akka.util.Timeout
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit._
import scala.collection.immutable.{Seq, Iterable, HashMap}
import scala.concurrent.util.duration._
import scala.concurrent.util.{Duration, FiniteDuration}
import scala.concurrent.{Promise, Await, Future}
import scala.util.control.NonFatal
import scala.annotation.switch

// TODO: doc defaults in reference.conf
class RemotingConfig(config: Config) {

  import config._

  val FailureDetectorThreshold: Double = getDouble("akka.remoting.failure-detector.threshold")

  val FailureDetectorMaxSampleSize: Int = getInt("akka.remoting.failure-detector.max-sample-size")

  val FailureDetectorStdDeviation: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.failure-detector.min-std-deviation"), MILLISECONDS)

  val AcceptableHeartBeatPause: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.failure-detector.acceptable-heartbeat-pause"), MILLISECONDS)

  val HeartBeatInterval: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.heartbeat-interval"), MILLISECONDS)

  val WaitActivityEnabled: Boolean = getBoolean("akka.remoting.wait-activity-enabled")

  val BackoffPeriod: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.backoff-interval"), MILLISECONDS)

  val RequireCookie: Boolean = getBoolean("akka.remoting.require-cookie")

  val SecureCookie: String = getString("akka.remoting.secure-cookie")

  val ShutdownTimeout: FiniteDuration = Duration(getMilliseconds("akka.remoting.shutdown-timeout"), MILLISECONDS)

  val StartupTimeout: FiniteDuration = Duration(getMilliseconds("akka.remoting.startup-timeout"), MILLISECONDS)

  val RetryLatchClosedFor: Long = getMilliseconds("akka.remoting.retry-latch-closed-for")

  val UsePassiveConnections: Boolean = getBoolean("akka.remoting.use-passive-connections")

}

object Remoting {

  val HeadActorName = "remoteTransportHeadActor"

  def transportAndAddressFor(transportMapping: Map[String, Set[(Transport, Address)]], remote: Address)
  :(Transport, Address) = {

    transportMapping.get(remote.protocol) match {
      case Some(transports) =>
        val responsibleTransports = transports.filter(_._1.isResponsibleFor(remote))
        (responsibleTransports.size: @switch) match {
          case 0 =>
            throw new RemoteTransportException(
              s"No transport is responsible for address: ${remote} although protocol ${remote.protocol} is available",
              null)

          case 1 =>
            responsibleTransports.head

          case _ =>
            throw new RemoteTransportException(
              s"Multiple transports are available for ${remote}: ${responsibleTransports.mkString(",")}",
              null)
        }
      case None => throw new RemoteTransportException(s"No transport is loaded for protocol: ${remote.protocol}", null)
    }
  }

}

class Remoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  import Remoting._

  private val settings = new RemotingConfig(provider.remoteSettings.config)

  @volatile private var headActor: ActorRef = _
  @volatile var transportMapping: Map[String, Set[(Transport, Address)]] = _
  @volatile var addresses: Set[Address] = _

  override def localAddressForRemote(remote: Address): Address = transportAndAddressFor(transportMapping, remote)._2

  val log: LoggingAdapter = Logging(system.eventStream, s"ActorManagedRemoting($addresses)")

  override def shutdown(): Unit = {
    if (headActor != null) {
      try {
        val stopped: Future[Boolean] = gracefulStop(headActor, 5 seconds)(system)
        // the actor has been stopped
        if (Await.result(stopped, settings.ShutdownTimeout)) {
          log.info("Remoting stopped successfully")
        }

      } catch {
        case e: java.util.concurrent.TimeoutException ⇒ log.warning("Shutdown timed out")
        case NonFatal(e)                         ⇒ log.error(e, "Shutdown failed")
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
        Props(new HeadActor(settings)), Remoting.HeadActorName)

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
    }
  }

  // TODO: this is called in RemoteActorRefProvider to handle the lifecycle of connections (clients)
  // Originally calls back to RemoteTransport, but now this should be handled as the lifecycle of actors
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

  //TODO: use these settings
  protected def useUntrustedMode: Boolean = provider.remoteSettings.UntrustedMode

  protected def logRemoteLifeCycleEvents: Boolean = provider.remoteSettings.LogRemoteLifeCycleEvents

}

private[transport] object HeadActor {
  sealed trait RemotingCommand
  case class Listen(addressesPromise: Promise[Set[(Transport, Address)]]) extends RemotingCommand
  case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand {
    override def toString = s"Remote message $senderOption -> $recipient"
  }

  sealed trait EndpointPolicy
  case class Pass(endpoint: ActorRef) extends EndpointPolicy
  case class Latched(timeOfFailure: Long) extends EndpointPolicy
    //TODO: implement quarantine

  case object Prune

  class EndpointRegistry {
    @volatile private var addressToEndpointAndPolicy = HashMap[Address, EndpointPolicy]()
    @volatile private var endpointToAddress = HashMap[ActorRef, Address]()

    def getEndpointWithPolicy(address: Address) = addressToEndpointAndPolicy.get(address)

    def prune(pruneAge: Long) {
      addressToEndpointAndPolicy = addressToEndpointAndPolicy.filter {
        case (_, Pass(_))               ⇒ true
        case (_, Latched(timeOfFailure)) ⇒ timeOfFailure + pruneAge < System.nanoTime()
      }
    }

    def registerEndpoint(address: Address, endpoint: ActorRef) {
      addressToEndpointAndPolicy = addressToEndpointAndPolicy + (address -> Pass(endpoint))
      endpointToAddress = endpointToAddress + (endpoint -> address)
    }

    def markFailed(endpoint: ActorRef, timeOfFailure: Long) {
      addressToEndpointAndPolicy += endpointToAddress(endpoint) -> Latched(timeOfFailure)
      endpointToAddress = endpointToAddress - endpoint
    }

    def markPass(endpoint: ActorRef) {
      addressToEndpointAndPolicy += endpointToAddress(endpoint) -> Pass(endpoint)
    }

    def removeIfNotLatched(endpoint: ActorRef) {
      endpointToAddress.get(endpoint) foreach { address =>
        addressToEndpointAndPolicy.get(address) match {
          case Some(Latched(_)) ⇒ //Leave it be. It contains only the last failure time, but not the endpoint ref
          case _ ⇒
            addressToEndpointAndPolicy = addressToEndpointAndPolicy - address
            endpointToAddress = endpointToAddress - endpoint
        }
      }

    }
  }
}

private[transport] class HeadActor(private val settings: RemotingConfig) extends Actor {

  import HeadActor._
  import context.dispatcher

  private val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]
  private val log = Logging(context.system.eventStream, "HeadActor")

  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  private val endpoints = new EndpointRegistry
  private var transports: List[Transport] = List()
  private var transportMapping: Map[String, Set[(Transport, Address)]] = Map()

  private val retryLatchEnabled = settings.RetryLatchClosedFor > 0L
  val pruneInterval: Long = if (retryLatchEnabled) settings.RetryLatchClosedFor * 2L else 0L

  private def failureStrategy = if (!retryLatchEnabled) {
    // This strategy keeps all the messages in the stash of the endpoint so restart will transfer the queue
    // to the restarted endpoint -- thus no messages are lost
    Restart
  } else {
    // This strategy throws away all the messages enqueued in the endpoint (in its stash), registers the time of failure,
    // keeps throwing away messages until the retry latch becomes open (RetryLatchClosedFor)
    endpoints.markFailed(sender, System.nanoTime())
    Stop
  }

  override val supervisorStrategy = OneForOneStrategy() {
    case NonFatal(_)          ⇒ failureStrategy
  }

  override def preStart() {
    // TODO: Prune old latch entries (if latching is enabled) to avoid memleaks
    if (retryLatchEnabled) {
      //TODO: Cancellable
      context.system.scheduler.schedule(pruneInterval milliseconds, pruneInterval milliseconds, self, Prune)
    }

    loadTransports()
  }

  def receive = {
    case Listen(addressesPromise) ⇒
      initializeTransports(addressesPromise)

    case s @ Send(message, senderOption, recipientRef) ⇒
      val recipientAddress = recipientRef.path.address

      endpoints.getEndpointWithPolicy(recipientAddress) match {
        case Some(Pass(endpoint)) ⇒
          endpoint ! s

        case Some(Latched(timeOfFailure)) ⇒ if (retryLatchOpen(timeOfFailure)) {
          val endpoint = createEndpoint(recipientAddress, None)
          endpoints.markPass(endpoint)
          endpoint ! s
        } else {
          extendedSystem.deadLetters ! message
        }

        case None ⇒
          val endpoint = createEndpoint(recipientAddress, None)
          endpoints.registerEndpoint(recipientAddress, endpoint)
          endpoint ! s

      }


    case InboundAssociation(handle) ⇒
      val endpoint = createEndpoint(handle.remoteAddress, Some(handle))
      if (settings.UsePassiveConnections) {
        endpoints.registerEndpoint(handle.localAddress, endpoint)
      }

    case Terminated(endpoint) ⇒
      endpoints.removeIfNotLatched(endpoint)

    case Prune ⇒
      endpoints.prune(settings.RetryLatchClosedFor)
  }

  private def loadTransports(): Unit = ???

  private def initializeTransports(addressesPromise: Promise[Set[(Transport, Address)]]): Unit = {
    val listens: Future[Seq[(Transport, (Address, Promise[ActorRef]))]] = Future.sequence(
      transports.map { transport => transport.listen.map { transport -> _ } }
    )

    listens.onSuccess {
      case results =>
        val transportsAndAddreses = (for ((transport, (address, promise)) <- results) yield {
          promise.success(self)
          transport -> address
        }).toSet
        addressesPromise.success(transportsAndAddreses)

        transportMapping = transportsAndAddreses.groupBy { case (transport, _) => transport.schemeIdentifier }.mapValues {
          _.toSet
        }
    }

    listens.onFailure {
      case e: Throwable => addressesPromise.failure(e)
    }
  }

  private def createEndpoint(remoteAddress: Address, handleOption: Option[AssociationHandle]): ActorRef = {
    if (!transports.contains(remoteAddress.protocol)) {
      throw new RemoteTransportException(s"There is no transport registered for protocol ${remoteAddress.protocol}", null)
    } else {
      context.actorOf(Props(
        new EndpointWriter(
          !handleOption.isDefined,
          handleOption,
          remoteAddress,
          Remoting.transportAndAddressFor(transportMapping, remoteAddress)._1,
          settings,
          AkkaPduProtobufCodec)
      ))
    }
  }

  // TODO: de-correlate retries
  private def retryLatchOpen(timeOfFailure: Long): Boolean = (timeOfFailure + settings.RetryLatchClosedFor) > System.nanoTime()

  override def postStop(): Unit = {
    try {
      transports foreach { _.shutdown() }
      //notifier.remoteServerShutdown()
    } catch {
      case NonFatal(e) ⇒
        //notifier.remoteServerError(e)
        log.error(e, "Unable to shut down the underlying TransportConnector")
    }
  }

}