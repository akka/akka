/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery
package tcp

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.ConfigurationException
import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.dispatch.ExecutionContexts
import akka.event.Logging
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransportException
import akka.remote.artery.Decoder.InboundCompressionAccess
import akka.remote.artery.compress._
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.SharedKillSwitch
import akka.stream.SinkShape
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Partition
import akka.stream.scaladsl.RestartFlow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Tcp.ServerBinding
import akka.util.{ ByteString, OptionVal }
import akka.util.ccompat._

/**
 * INTERNAL API
 */
private[remote] object ArteryTcpTransport {

  private val successUnit = Success(())

  def optionToTry(opt: Option[Throwable]): Try[Unit] = opt match {
    case None    => successUnit
    case Some(t) => Failure(t)
  }
}

/**
 * INTERNAL API
 */
@ccompatUsedUntil213
private[remote] class ArteryTcpTransport(
    _system: ExtendedActorSystem,
    _provider: RemoteActorRefProvider,
    tlsEnabled: Boolean)
    extends ArteryTransport(_system, _provider) {
  import ArteryTransport._
  import ArteryTcpTransport._
  import FlightRecorderEvents._

  override type LifeCycle = NotUsed

  // may change when inbound streams are restarted
  @volatile private var inboundKillSwitch: SharedKillSwitch = KillSwitches.shared("inboundKillSwitch")
  // may change when inbound streams are restarted
  @volatile private var inboundStream: OptionVal[Sink[EnvelopeBuffer, NotUsed]] = OptionVal.None
  @volatile private var serverBinding: Option[ServerBinding] = None

  private val sslEngineProvider: OptionVal[SSLEngineProvider] =
    if (tlsEnabled) {
      system.settings.setup.get[SSLEngineProviderSetup] match {
        case Some(p) =>
          OptionVal.Some(p.sslEngineProvider(system))
        case None =>
          // load from config
          OptionVal.Some(
            system.dynamicAccess
              .createInstanceFor[SSLEngineProvider](
                settings.SSLEngineProviderClassName,
                List((classOf[ActorSystem], system)))
              .recover {
                case e =>
                  throw new ConfigurationException(
                    s"Could not create SSLEngineProvider [${settings.SSLEngineProviderClassName}]",
                    e)
              }
              .get)
      }
    } else OptionVal.None

  override protected def startTransport(): Unit = {
    // nothing specific here
  }

  override protected def outboundTransportSink(
      outboundContext: OutboundContext,
      streamId: Int,
      bufferPool: EnvelopeBufferPool): Sink[EnvelopeBuffer, Future[Done]] = {
    implicit val sys: ActorSystem = system

    val afr = createFlightRecorderEventSink()

    val host = outboundContext.remoteAddress.host.get
    val port = outboundContext.remoteAddress.port.get
    val remoteAddress = InetSocketAddress.createUnresolved(host, port)

    def connectionFlow: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] =
      if (tlsEnabled) {
        val sslProvider = sslEngineProvider.get
        Tcp().outgoingTlsConnectionWithSSLEngine(
          remoteAddress,
          createSSLEngine = () => sslProvider.createClientSSLEngine(host, port),
          connectTimeout = settings.Advanced.Tcp.ConnectionTimeout,
          verifySession = session => optionToTry(sslProvider.verifyClientSession(host, session)))
      } else {
        Tcp().outgoingConnection(
          remoteAddress,
          halfClose = true, // issue https://github.com/akka/akka/issues/24392 if set to false
          connectTimeout = settings.Advanced.Tcp.ConnectionTimeout)
      }

    def connectionFlowWithRestart: Flow[ByteString, ByteString, NotUsed] = {
      val restartCount = new AtomicInteger(0)

      val flowFactory = () => {
        val onFailureLogLevel = if (restartCount.incrementAndGet() == 1) Logging.WarningLevel else Logging.DebugLevel

        def flow(controlIdleKillSwitch: OptionVal[SharedKillSwitch]) =
          Flow[ByteString]
            .via(Flow.lazyInitAsync(() => {
              // only open the actual connection if any new messages are sent
              afr.loFreq(
                TcpOutbound_Connected,
                s"${outboundContext.remoteAddress.host.get}:${outboundContext.remoteAddress.port.get} " +
                s"/ ${streamName(streamId)}")
              if (controlIdleKillSwitch.isDefined)
                outboundContext.asInstanceOf[Association].setControlIdleKillSwitch(controlIdleKillSwitch)
              Future.successful(
                Flow[ByteString]
                  .prepend(Source.single(TcpFraming.encodeConnectionHeader(streamId)))
                  .via(connectionFlow))
            }))
            .recoverWithRetries(1, { case ArteryTransport.ShutdownSignal => Source.empty })
            .log(name = s"outbound connection to [${outboundContext.remoteAddress}], ${streamName(streamId)} stream")
            .addAttributes(Attributes.logLevels(onElement = LogLevels.Off, onFailure = onFailureLogLevel))

        if (streamId == ControlStreamId) {
          // must replace the KillSwitch when restarted
          val controlIdleKillSwitch = KillSwitches.shared("outboundControlStreamIdleKillSwitch")
          Flow[ByteString].via(controlIdleKillSwitch.flow).via(flow(OptionVal.Some(controlIdleKillSwitch)))
        } else {
          flow(OptionVal.None)
        }
      }

      val maxRestarts = if (streamId == ControlStreamId) Int.MaxValue else 3
      // Restart of inner connection part important in control stream, since system messages
      // are buffered and resent from the outer SystemMessageDelivery stage. No maxRestarts limit for control
      // stream. For message stream it's best effort retry a few times.
      RestartFlow
        .withBackoff[ByteString, ByteString](
          settings.Advanced.OutboundRestartBackoff,
          settings.Advanced.OutboundRestartBackoff * 5,
          0.1,
          maxRestarts)(flowFactory)
        // silence "Restarting graph due to failure" logging by RestartFlow
        .addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))

    }

    Flow[EnvelopeBuffer]
      .map { env =>
        val size = env.byteBuffer.limit()
        afr.hiFreq(TcpOutbound_Sent, size)

        // TODO Possible performance improvement, could we reduce the copying of bytes?
        val bytes = ByteString(env.byteBuffer)
        bufferPool.release(env)

        TcpFraming.encodeFrameHeader(size) ++ bytes
      }
      .via(connectionFlowWithRestart)
      .map(_ =>
        throw new IllegalStateException(
          s"Unexpected incoming bytes in outbound connection to [${outboundContext.remoteAddress}]"))
      .toMat(Sink.ignore)(Keep.right)
  }

  override protected def runInboundStreams(): Int = {
    // Design note: The design of how to run the inbound streams are influenced by the original design
    // for the Aeron streams, and there we can only have one single inbound since everything comes in
    // via the single AeronSource.
    //
    // For TCP we could materialize the inbound streams for each inbound connection, i.e. running many
    // completely separate inbound streams. Each would still have to include all 3 control, ordinary,
    // large parts for each connection even though only one is used by a specific connection. Unless
    // we can dynamically choose what to materialize based on the `streamId` (as a first byte, or
    // in first frame of the connection), which is complicated.
    //
    // However, this would be make the design for Aeron and TCP more different and more things might
    // have to be changed, such as compression advertisements and materialized values. Number of
    // inbound streams would be dynamic, and so on.

    implicit val mat: Materializer = materializer
    implicit val sys: ActorSystem = system

    // These streams are always running, only one instance of each inbound stream, and then the inbound connections
    // are attached to these via a MergeHub.
    val (controlStream, controlStreamCompleted) = runInboundControlStream()
    val (ordinaryMessagesStream, ordinaryMessagesStreamCompleted) = runInboundOrdinaryMessagesStream()
    val (largeMessagesStream, largeMessagesStreamCompleted) = {
      if (largeMessageChannelEnabled)
        runInboundLargeMessagesStream()
      else
        (
          Flow[EnvelopeBuffer]
            .map(_ => log.warning("Dropping large message, missing large-message-destinations configuration."))
            .to(Sink.ignore),
          Promise[Done]().future) // never completed, not enabled
    }

    // An inbound connection will only use one of the control, ordinary or large streams, but we have to
    // attach it to all and select via Partition and the streamId in the frame header. Conceptually it
    // would have been better to send the streamId as one single first byte for a new connection and
    // decide where to attach it based on that byte. Then the streamId wouldn't have to be sent in each
    // frame. That was not chosen because it is more complicated to implement and might have more runtime
    // overhead.
    inboundStream = OptionVal.Some(Sink.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val partition = b.add(Partition[EnvelopeBuffer](3, env => {
        env.streamId match {
          case OrdinaryStreamId => 1
          case ControlStreamId  => 0
          case LargeStreamId    => 2
          case other            => throw new IllegalArgumentException(s"Unexpected streamId [$other]")
        }
      }))
      partition.out(0) ~> controlStream
      partition.out(1) ~> ordinaryMessagesStream
      partition.out(2) ~> largeMessagesStream
      SinkShape(partition.in)
    }))

    // If something in the inboundConnectionFlow fails, e.g. framing, the connection will be teared down,
    // but other parts of the inbound streams don't have to restarted.
    def inboundConnectionFlow: Flow[ByteString, ByteString, NotUsed] = {
      // must create new Flow for each connection because of the FlightRecorder that can't be shared
      val afr = createFlightRecorderEventSink()
      Flow[ByteString]
        .via(inboundKillSwitch.flow)
        .via(new TcpFraming(afr))
        .alsoTo(inboundStream.get)
        .filter(_ => false) // don't send back anything in this TCP socket
        .map(_ => ByteString.empty) // make it a Flow[ByteString] again
    }

    val bindHost = settings.Bind.Hostname
    val bindPort =
      if (settings.Bind.Port == 0 && settings.Canonical.Port == 0)
        localAddress.address.port match {
          case Some(n) => n
          case _       => 0
        } else settings.Bind.Port

    val connectionSource: Source[Tcp.IncomingConnection, Future[ServerBinding]] =
      if (tlsEnabled) {
        val sslProvider = sslEngineProvider.get
        Tcp().bindTlsWithSSLEngine(
          interface = bindHost,
          port = bindPort,
          createSSLEngine = () => sslProvider.createServerSSLEngine(bindHost, bindPort),
          verifySession = session => optionToTry(sslProvider.verifyServerSession(bindHost, session)))
      } else {
        Tcp().bind(interface = bindHost, port = bindPort, halfClose = false)
      }

    serverBinding = serverBinding match {
      case None =>
        val afr = createFlightRecorderEventSink()
        val binding = connectionSource
          .to(Sink.foreach { connection =>
            afr.loFreq(
              TcpInbound_Connected,
              s"${connection.remoteAddress.getHostString}:${connection.remoteAddress.getPort}")
            connection.handleWith(inboundConnectionFlow)
          })
          .run()
          .recoverWith {
            case e =>
              Future.failed(new RemoteTransportException(
                s"Failed to bind TCP to [${localAddress.address.host.get}:${localAddress.address.port.get}] due to: " +
                e.getMessage,
                e))
          }(ExecutionContexts.sameThreadExecutionContext)

        // only on initial startup, when ActorSystem is starting
        val b = Await.result(binding, settings.Bind.BindTimeout)
        afr.loFreq(TcpInbound_Bound, s"$bindHost:${b.localAddress.getPort}")
        Some(b)
      case s @ Some(_) =>
        // already bound, when restarting
        s
    }

    // Failures in any of the inbound streams should be extremely rare, probably an unforeseen accident.
    // Tear down everything and start over again. Inbound streams are "stateless" so that should be fine.
    // Tested in SurviveInboundStreamRestartWithCompressionInFlightSpec
    implicit val ec: ExecutionContext = materializer.executionContext
    val completed = Future.firstCompletedOf(
      List(controlStreamCompleted, ordinaryMessagesStreamCompleted, largeMessagesStreamCompleted))
    val restart = () => {
      inboundKillSwitch.shutdown()
      inboundKillSwitch = KillSwitches.shared("inboundKillSwitch")

      val allStopped: Future[Done] = for {
        _ <- controlStreamCompleted.recover { case _          => Done }
        _ <- ordinaryMessagesStreamCompleted.recover { case _ => Done }
        _ <- if (largeMessageChannelEnabled)
          largeMessagesStreamCompleted.recover { case _ => Done } else Future.successful(Done)
      } yield Done
      allStopped.foreach(_ => runInboundStreams())
    }

    attachInboundStreamRestart("Inbound streams", completed, restart)

    serverBinding.get.localAddress.getPort
  }

  private def runInboundControlStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (hub, ctrl, completed) =
      MergeHub
        .source[EnvelopeBuffer]
        .addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
        .via(inboundKillSwitch.flow)
        .via(inboundFlow(settings, NoInboundCompressions))
        .toMat(inboundControlSink)({ case (a, (c, d)) => (a, c, d) })
        .run()(controlMaterializer)
    attachControlMessageObserver(ctrl)
    updateStreamMatValues(completed)

    (hub, completed)
  }

  private def runInboundOrdinaryMessagesStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (inboundHub: Sink[EnvelopeBuffer, NotUsed], inboundCompressionAccess, completed) =
      if (inboundLanes == 1) {
        MergeHub
          .source[EnvelopeBuffer]
          .addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
          .via(inboundKillSwitch.flow)
          .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
          .toMat(inboundSink(envelopeBufferPool))({ case ((a, b), c) => (a, b, c) })
          .run()(materializer)

      } else {
        // TODO perhaps a few more things can be extracted and DRY with AeronUpdTransport.runInboundOrdinaryMessagesStream
        val laneKillSwitch = KillSwitches.shared("laneKillSwitch")
        val laneSource: Source[InboundEnvelope, (Sink[EnvelopeBuffer, NotUsed], InboundCompressionAccess)] =
          MergeHub
            .source[EnvelopeBuffer]
            .addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
            .via(inboundKillSwitch.flow)
            .via(laneKillSwitch.flow)
            .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
            .via(Flow.fromGraph(new DuplicateHandshakeReq(inboundLanes, this, system, envelopeBufferPool)))

        val (inboundHub, compressionAccess, laneHub) =
          laneSource
            .toMat(
              Sink.fromGraph(
                new FixedSizePartitionHub[InboundEnvelope](
                  inboundLanePartitioner,
                  inboundLanes,
                  settings.Advanced.InboundHubBufferSize)))({
              case ((a, b), c) => (a, b, c)
            })
            .run()(materializer)

        val lane = inboundSink(envelopeBufferPool)
        val completedValues: Vector[Future[Done]] =
          (0 until inboundLanes).iterator
            .map { _ =>
              laneHub.toMat(lane)(Keep.right).run()(materializer)
            }
            .to(immutable.Vector)

        implicit val ec = system.dispatchers.internalDispatcher

        // tear down the upstream hub part if downstream lane fails
        // lanes are not completed with success by themselves so we don't have to care about onSuccess
        Future.firstCompletedOf(completedValues).failed.foreach { reason =>
          laneKillSwitch.abort(reason)
        }
        val allCompleted = Future.sequence(completedValues).map(_ => Done)

        (inboundHub, compressionAccess, allCompleted)
      }

    setInboundCompressionAccess(inboundCompressionAccess)

    updateStreamMatValues(completed)

    (inboundHub, completed)
  }

  private def runInboundLargeMessagesStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (hub, completed) =
      MergeHub
        .source[EnvelopeBuffer]
        .addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
        .via(inboundKillSwitch.flow)
        .via(inboundLargeFlow(settings))
        .toMat(inboundSink(largeEnvelopeBufferPool))(Keep.both)
        .run()(materializer)

    updateStreamMatValues(completed)

    (hub, completed)
  }

  private def updateStreamMatValues(completed: Future[Done]): Unit = {
    implicit val ec: ExecutionContext = materializer.executionContext
    updateStreamMatValues(
      ControlStreamId,
      InboundStreamMatValues[NotUsed](NotUsed, completed.recover { case _ => Done }))
  }

  override protected def shutdownTransport(): Future[Done] = {
    implicit val ec = system.dispatchers.internalDispatcher
    inboundKillSwitch.shutdown()
    unbind().map { _ =>
      topLevelFlightRecorder.loFreq(Transport_Stopped, NoMetaData)
      Done
    }
  }

  private def unbind(): Future[Done] = {
    serverBinding match {
      case Some(binding) =>
        implicit val ec = system.dispatchers.internalDispatcher
        for {
          _ <- binding.unbind()
        } yield {
          topLevelFlightRecorder.loFreq(
            TcpInbound_Bound,
            s"${localAddress.address.host.get}:${localAddress.address.port}")
          Done
        }
      case None =>
        Future.successful(Done)
    }
  }

}
