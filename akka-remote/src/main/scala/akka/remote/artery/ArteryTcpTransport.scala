/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
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
import akka.stream.FlowShape
import akka.stream.Graph
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.SharedKillSwitch
import akka.stream.SinkShape
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Partition
import akka.stream.scaladsl.RestartFlow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Tcp.ServerBinding
import akka.util.ByteString
import akka.util.OptionVal

/**
 * INTERNAL API
 */
private[remote] object ArteryTcpTransport {

  private val successUnit = Success(())

  def optionToTry(opt: Option[Throwable]): Try[Unit] = opt match {
    case None    ⇒ successUnit
    case Some(t) ⇒ Failure(t)
  }
}

/**
 * INTERNAL API
 */
private[remote] class ArteryTcpTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider,
                                         tlsEnabled: Boolean)
  extends ArteryTransport(_system, _provider) {
  import ArteryTransport._
  import ArteryTcpTransport._
  import FlightRecorderEvents._

  // may change when inbound streams are restarted
  @volatile private var inboundKillSwitch: SharedKillSwitch = KillSwitches.shared("inboundKillSwitch")
  // may change when inbound streams are restarted
  @volatile private var inboundConnectionFlow: OptionVal[Flow[ByteString, ByteString, NotUsed]] = OptionVal.None
  @volatile private var serverBinding: Option[Future[ServerBinding]] = None

  private val sslEngineProvider: OptionVal[SSLEngineProvider] =
    if (tlsEnabled) {
      OptionVal.Some(system.dynamicAccess.createInstanceFor[SSLEngineProvider](
        settings.SSLEngineProviderClassName,
        List((classOf[ActorSystem], system))).recover {
          case e ⇒ throw new ConfigurationException(
            s"Could not create SSLEngineProvider [${settings.SSLEngineProviderClassName}]", e)
        }.get)
    } else OptionVal.None

  override protected def startTransport(): Unit = {
    // FIXME new TCP events
    topLevelFREvents.loFreq(Transport_AeronStarted, NoMetaData)
  }

  override protected def outboundTransportSink(
    outboundContext: OutboundContext,
    streamId:        Int,
    bufferPool:      EnvelopeBufferPool): Sink[EnvelopeBuffer, Future[Done]] = {
    implicit val sys: ActorSystem = system

    val host = outboundContext.remoteAddress.host.get
    val port = outboundContext.remoteAddress.port.get
    val remoteAddress = InetSocketAddress.createUnresolved(host, port)

    def connectionFlow: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] =
      if (tlsEnabled) {
        val sslProvider = sslEngineProvider.get
        Tcp().outgoingTlsConnectionWithSSLEngine(
          remoteAddress,
          createSSLEngine = () ⇒ sslProvider.createClientSSLEngine(host, port),
          connectTimeout = settings.Advanced.ConnectionTimeout,
          verifySession = session ⇒ optionToTry(sslProvider.verifyClientSession(host, session)))
      } else {
        Tcp()
          .outgoingConnection(
            remoteAddress,
            halfClose = true, // issue https://github.com/akka/akka/issues/24392 if set to false
            connectTimeout = settings.Advanced.ConnectionTimeout)
      }

    def connectionFlowWithRestart: Flow[ByteString, ByteString, NotUsed] = {
      val flowFactory = () ⇒ connectionFlow.mapMaterializedValue(_ ⇒ NotUsed)
        .recoverWithRetries(1, { case ArteryTransport.ShutdownSignal ⇒ Source.empty })
        .log(name = s"outbound connection to [${outboundContext.remoteAddress}], ${streamName(streamId)} stream")
        .addAttributes(Attributes.logLevels(onElement = LogLevels.Off, onFailure = Logging.WarningLevel))

      if (streamId == ControlStreamId) {
        // restart of inner connection part important in control flow, since system messages
        // are buffered and resent from the outer SystemMessageDelivery stage.
        RestartFlow.withBackoff[ByteString, ByteString](
          settings.Advanced.OutboundRestartBackoff,
          settings.Advanced.GiveUpSystemMessageAfter, 0.1)(flowFactory)
      } else {
        // Best effort retry a few times
        // FIXME only restart on failures?, but missing in RestartFlow, see https://github.com/akka/akka/pull/23911
        RestartFlow.withBackoff[ByteString, ByteString](
          settings.Advanced.OutboundRestartBackoff,
          settings.Advanced.OutboundRestartBackoff * 5, 0.1, maxRestarts = 3)(flowFactory)
      }

    }

    Flow[EnvelopeBuffer]
      .map { env ⇒
        // TODO Possible performance improvement, could we reduce the copying of bytes?
        val bytes = ByteString(env.byteBuffer)
        bufferPool.release(env)
        bytes
      }
      .via(connectionFlowWithRestart)
      .map(_ ⇒ throw new IllegalStateException(s"Unexpected incoming bytes in outbound connection to [${outboundContext.remoteAddress}]"))
      .toMat(Sink.ignore)(Keep.right)
  }

  override protected def runInboundStreams(): Unit = {

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

    // These streams are always running, on instance of each, and then the inbound connections
    // are attached to these via a MergeHub.
    val (controlStream, controlStreamCompleted) = runInboundControlStream()
    val (ordinaryMessagesStream, ordinaryMessagesStreamCompleted) = runInboundOrdinaryMessagesStream()
    val (largeMessagesStream, largeMessagesStreamCompleted) = {
      if (largeMessageChannelEnabled)
        runInboundLargeMessagesStream()
      else
        (
          Flow[EnvelopeBuffer]
          .map(_ ⇒ log.warning("Dropping large message, missing large-message-destinations configuration."))
          .to(Sink.ignore),
          Promise[Done]().future) // never completed, not enabled
    }

    // An inbound connection will only use one of the control, ordinary or large streams, but we have to
    // attach it to all and select via Partition and the streamId in the frame header. Conceptually it
    // would have been better to send the streamId as one single first byte for a new connection and
    // decide where to attach it based on that byte. Then the streamId wouldn't have to be sent in each
    // frame. That was not chosen because it is more complicated to implement and might have more runtime
    // overhead.
    // TODO Perhaps possible performance/scalability idea for investigation is to use `recoverWith` instead
    // of `Partition`. First attach it to the `ordinaryMessagesStream` and when a non matching streamId
    // arrives throw a specific exception and recoverWith `controlStream` and then largeMessagesStream.
    val inboundStream: Sink[EnvelopeBuffer, NotUsed] =
      Sink.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val partition = b.add(Partition[EnvelopeBuffer](3, env ⇒ {
          env.byteBuffer.get(EnvelopeBuffer.StreamIdOffset).toInt match {
            case OrdinaryStreamId ⇒ 1
            case ControlStreamId  ⇒ 0
            case LargeStreamId    ⇒ 2
            case other            ⇒ throw new IllegalArgumentException(s"Unexpected streamId [$other]")
          }
        }))
        partition.out(0) ~> controlStream
        partition.out(1) ~> ordinaryMessagesStream
        partition.out(2) ~> largeMessagesStream
        SinkShape(partition.in)
      })

    val maxFrameSize =
      if (largeMessageChannelEnabled) settings.Advanced.MaximumLargeFrameSize
      else settings.Advanced.MaximumFrameSize

    // TODO `Flow.alsoTo` is currently using eagerCancel = false, which is not what I want in the
    // inboundConnectionFlow. If that is changed (see issue #24291) this custom impl can be replaced.
    def alsoToEagerCancel[M](that: Graph[SinkShape[EnvelopeBuffer], M]): Graph[FlowShape[EnvelopeBuffer, EnvelopeBuffer], M] =
      GraphDSL.create(that) { implicit b ⇒ r ⇒
        import GraphDSL.Implicits._
        val bcast = b.add(Broadcast[EnvelopeBuffer](2, eagerCancel = true))
        bcast.out(1) ~> r
        FlowShape(bcast.in, bcast.out(0))
      }

    // If something in the inboundConnectionFlow fails, e.g. framing, the connection will be teared down,
    // but other parts of the inbound streams don't have to restarted.
    inboundConnectionFlow = OptionVal.Some(Flow[ByteString]
      .via(inboundKillSwitch.flow)
      .via(Framing.lengthField(fieldLength = 4, fieldOffset = EnvelopeBuffer.FrameLengthOffset,
        maxFrameSize, byteOrder = ByteOrder.LITTLE_ENDIAN))
      .map { frame ⇒
        val buffer = ByteBuffer.wrap(frame.toArray)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        new EnvelopeBuffer(buffer)
      }
      .via(alsoToEagerCancel(inboundStream))
      .filter(_ ⇒ false) // don't send back anything in this TCP socket
      .map(_ ⇒ ByteString.empty) // make it a Flow[ByteString] again
    )

    val host = localAddress.address.host.get
    val port = localAddress.address.port.get

    val connectionSource: Source[Tcp.IncomingConnection, Future[ServerBinding]] =
      if (tlsEnabled) {
        val sslProvider = sslEngineProvider.get
        Tcp().bindTlsWithSSLEngine(
          interface = host,
          port = port,
          createSSLEngine = () ⇒ sslProvider.createServerSSLEngine(host, port),
          verifySession = session ⇒ optionToTry(sslProvider.verifyServerSession(host, session)))
      } else {
        Tcp().bind(
          interface = host,
          port = port,
          halfClose = false)
      }

    serverBinding = serverBinding match {
      case None ⇒
        val binding = connectionSource
          .to(Sink.foreach { connection ⇒
            connection.handleWith(inboundConnectionFlow.get)
          })
          .run()
          .recoverWith {
            case e ⇒ Future.failed(new RemoteTransportException(
              s"Failed to bind TCP to [${localAddress.address.host.get}:${localAddress.address.port.get}] due to: " +
                e.getMessage, e))
          }(ExecutionContexts.sameThreadExecutionContext)

        // only on initial startup, when ActorSystem is starting
        Await.result(binding, settings.Bind.BindTimeout)
        Some(binding)
      case s @ Some(_) ⇒
        // already bound, when restarting
        s
    }

    // Failures in any of the inbound streams should be extremely rare, probably an unforeseen accident.
    // Tear down everything and start over again. Inbound streams are "stateless" so that should be fine.
    // Tested in SurviveInboundStreamRestartWithCompressionInFlightSpec
    implicit val ec: ExecutionContext = materializer.executionContext
    val completed = Future.firstCompletedOf(
      List(controlStreamCompleted, ordinaryMessagesStreamCompleted, largeMessagesStreamCompleted))
    val restart = () ⇒ {
      inboundKillSwitch.shutdown()
      inboundKillSwitch = KillSwitches.shared("inboundKillSwitch")

      val allStopped: Future[Done] = for {
        _ ← controlStreamCompleted.recover { case _ ⇒ Done }
        _ ← ordinaryMessagesStreamCompleted.recover { case _ ⇒ Done }
        _ ← if (largeMessageChannelEnabled)
          largeMessagesStreamCompleted.recover { case _ ⇒ Done } else Future.successful(Done)
      } yield Done
      allStopped.foreach(_ ⇒ runInboundStreams())
    }

    attachInboundStreamRestart("Inbound streams", completed, restart)
  }

  private def runInboundControlStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (hub, ctrl, completed) =
      MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
        .via(inboundKillSwitch.flow)
        .via(inboundFlow(settings, NoInboundCompressions))
        .toMat(inboundControlSink)({ case (a, (c, d)) ⇒ (a, c, d) })
        .run()(controlMaterializer)
    attachControlMessageObserver(ctrl)
    implicit val ec: ExecutionContext = materializer.executionContext
    updateStreamMatValues(ControlStreamId, completed)

    (hub, completed)
  }

  private def runInboundOrdinaryMessagesStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (inboundHub: Sink[EnvelopeBuffer, NotUsed], inboundCompressionAccess, completed) =
      if (inboundLanes == 1) {
        MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
          .via(inboundKillSwitch.flow)
          .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
          .toMat(inboundSink(envelopeBufferPool))({ case ((a, b), c) ⇒ (a, b, c) })
          .run()(materializer)

      } else {
        // TODO perhaps a few more things can be extracted and DRY with AeronUpdTransport.runInboundOrdinaryMessagesStream
        val laneKillSwitch = KillSwitches.shared("laneKillSwitch")
        val laneSource: Source[InboundEnvelope, (Sink[EnvelopeBuffer, NotUsed], InboundCompressionAccess)] =
          MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
            .via(inboundKillSwitch.flow)
            .via(laneKillSwitch.flow)
            .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
            .via(Flow.fromGraph(new DuplicateHandshakeReq(inboundLanes, this, system, envelopeBufferPool)))

        val (inboundHub, compressionAccess, laneHub) =
          laneSource
            .toMat(Sink.fromGraph(new FixedSizePartitionHub[InboundEnvelope](inboundLanePartitioner, inboundLanes,
              settings.Advanced.InboundHubBufferSize)))({ case ((a, b), c) ⇒ (a, b, c) })
            .run()(materializer)

        val lane = inboundSink(envelopeBufferPool)
        val completedValues: Vector[Future[Done]] =
          (0 until inboundLanes).map { _ ⇒
            laneHub.toMat(lane)(Keep.right).run()(materializer)
          }(collection.breakOut)

        import system.dispatcher

        // tear down the upstream hub part if downstream lane fails
        // lanes are not completed with success by themselves so we don't have to care about onSuccess
        Future.firstCompletedOf(completedValues).failed.foreach { reason ⇒ laneKillSwitch.abort(reason) }
        val allCompleted = Future.sequence(completedValues).map(_ ⇒ Done)

        (inboundHub, compressionAccess, allCompleted)
      }

    setInboundCompressionAccess(inboundCompressionAccess)

    updateStreamMatValues(OrdinaryStreamId, completed)

    (inboundHub, completed)
  }

  private def runInboundLargeMessagesStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (hub, completed) =
      MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
        .via(inboundKillSwitch.flow)
        .via(inboundLargeFlow(settings))
        .toMat(inboundSink(largeEnvelopeBufferPool))(Keep.both)
        .run()(materializer)

    updateStreamMatValues(LargeStreamId, completed)

    (hub, completed)
  }

  private def updateStreamMatValues(streamId: Int, completed: Future[Done]): Unit = {
    implicit val ec: ExecutionContext = materializer.executionContext
    updateStreamMatValues(ControlStreamId, InboundStreamMatValues(
      None,
      completed.recover { case _ ⇒ Done }))
  }

  override protected def shutdownTransport(): Future[Done] = {
    implicit val ec: ExecutionContext = materializer.executionContext
    inboundKillSwitch.shutdown()
    unbind().map { _ ⇒
      topLevelFREvents.loFreq(Transport_Stopped, NoMetaData)
      Done
    }
  }

  private def unbind(): Future[Done] = {
    implicit val ec: ExecutionContext = materializer.executionContext
    serverBinding match {
      case Some(binding) ⇒
        for {
          b ← binding
          _ ← b.unbind()
        } yield {
          Done
        }
      case None ⇒
        Future.successful(Done)
    }
  }

}
