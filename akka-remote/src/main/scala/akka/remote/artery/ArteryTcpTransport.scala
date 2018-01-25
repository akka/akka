/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._

import akka.Done
import akka.NotUsed
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
private[remote] class ArteryTcpTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider,
                                         tlsEnabled: Boolean)
  extends ArteryTransport(_system, _provider) {
  import ArteryTransport._
  import FlightRecorderEvents._

  @volatile private var inboundKillSwitch: SharedKillSwitch = KillSwitches.shared("inboundKillSwitch")
  private var serverBinding: Option[Future[ServerBinding]] = None

  private val sslEngineProvider: OptionVal[SSLEngineProvider] =
    // FIXME load from config
    if (tlsEnabled) OptionVal.Some(new ConfigSSLEngineProvider(system))
    else OptionVal.None

  override protected def startTransport(): Unit = {
    // FIXME new TCP events
    topLevelFREvents.loFreq(Transport_AeronStarted, NoMetaData)
  }

  override protected def outboundTransportSink(
    outboundContext: OutboundContext,
    streamId:        Int,
    bufferPool:      EnvelopeBufferPool): Sink[EnvelopeBuffer, Future[Done]] = {
    implicit val sys = system

    val host = outboundContext.remoteAddress.host.get
    val port = outboundContext.remoteAddress.port.get
    val remoteAddress = InetSocketAddress.createUnresolved(host, port)
    val connectionTimeout = 5.seconds // FIXME config

    def connectionFlow: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] =
      if (tlsEnabled) {
        Tcp().outgoingTlsConnectionWithSSLEngine(
          remoteAddress,
          createSSLEngine = () ⇒ sslEngineProvider.get.createClientSSLEngine(),
          connectTimeout = connectionTimeout)
      } else {
        Tcp()
          .outgoingConnection(
            remoteAddress,
            halfClose = true, // issue https://github.com/akka/akka/issues/24392 if set to false
            connectTimeout = connectionTimeout)
      }

    def connectionFlowWithRestart: Flow[ByteString, ByteString, NotUsed] = {
      val flowFactory = () ⇒ connectionFlow.mapMaterializedValue(_ ⇒ NotUsed)
        .recoverWithRetries(1, { case ArteryTransport.ShutdownSignal ⇒ Source.empty })
        .log(name = s"outbound connection to [${outboundContext.remoteAddress}], ${streamName(streamId)} stream")
        .addAttributes(Attributes.logLevels(onElement = LogLevels.Off, onFailure = Logging.WarningLevel))

      // FIXME config of backoff
      import scala.concurrent.duration._
      if (streamId == ControlStreamId) {
        // restart of inner connection part important in control flow, since system messages
        // are buffered and resent from the outer SystemMessageDelivery stage.
        // FIXME The mat value Future is currently never completed, because RestartFlow will retry forever, should it give up?
        //       related to give-up-system-message-after ?
        RestartFlow.withBackoff[ByteString, ByteString](1.second, 5.seconds, 0.1)(flowFactory)
      } else {
        // Best effort retry a few times
        // FIXME only restart on failures?, but missing in RestartFlow, see https://github.com/akka/akka/pull/23911
        RestartFlow.withBackoff[ByteString, ByteString](1.second, 5.seconds, 0.1, maxRestarts = 3)(flowFactory)
      }

    }

    Flow[EnvelopeBuffer]
      .map { env ⇒
        val bytes = ByteString(env.byteBuffer)
        bufferPool.release(env)
        bytes
      }
      .via(connectionFlowWithRestart)
      .map(_ ⇒ throw new IllegalStateException(s"Unexpected incoming bytes in outbound connection to [${outboundContext.remoteAddress}]"))
      .toMat(Sink.ignore)(Keep.right)
  }

  override protected def runInboundStreams(): Unit = {
    implicit val mat = materializer
    implicit val sys = system

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

    val inboundStream: Sink[EnvelopeBuffer, NotUsed] =
      Sink.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val partition = b.add(Partition[EnvelopeBuffer](3, env ⇒ {
          val streamId: Int = env.byteBuffer.get(EnvelopeBuffer.StreamIdOffset).toInt
          if (streamId == OrdinaryStreamId) 1
          else if (streamId == ControlStreamId) 0
          else if (streamId == LargeStreamId) 2
          else throw new IllegalArgumentException(s"Unexpected streamId [$streamId]")
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
    val inboundConnectionFlow = Flow[ByteString]
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

    val connectionSource: Source[Tcp.IncomingConnection, Future[ServerBinding]] =
      if (tlsEnabled) {
        Tcp().bindTlsWithSSLEngine(
          interface = localAddress.address.host.get,
          port = localAddress.address.port.get,
          createSSLEngine = () ⇒ sslEngineProvider.get.createServerSSLEngine())
      } else {
        Tcp().bind(
          interface = localAddress.address.host.get,
          port = localAddress.address.port.get,
          halfClose = false)
      }

    serverBinding =
      Some(
        connectionSource
          .to(Sink.foreach { connection ⇒
            connection.handleWith(inboundConnectionFlow)
          })
          .run()
          .recoverWith {
            case e ⇒ Future.failed(new RemoteTransportException(
              s"Failed to bind TCP to [${localAddress.address.host.get}:${localAddress.address.port.get}] due to: " +
                e.getMessage, e))
          }(ExecutionContexts.sameThreadExecutionContext)
      )

    Await.result(serverBinding.get, settings.Bind.BindTimeout)

    // Failures in any of the inbound streams should be extremely rare, probably an unforeseen accident.
    // Tear down everything and start over again. Inbound streams are "stateless" so that should be fine.
    // Tested in SurviveInboundStreamRestartWithCompressionInFlightSpec
    implicit val ec = materializer.executionContext
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
        // FIXME unbind is probably not needed, and might be more correct to not do that
        _ ← unbind().recover { case _ ⇒ Done }
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
    implicit val ec = materializer.executionContext
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
    implicit val ec = materializer.executionContext
    updateStreamMatValues(ControlStreamId, InboundStreamMatValues(
      None,
      completed.recover { case _ ⇒ Done }))
  }

  override protected def shutdownTransport(): Future[Done] = {
    implicit val ec = materializer.executionContext
    inboundKillSwitch.shutdown()
    unbind().map { _ ⇒
      topLevelFREvents.loFreq(Transport_Stopped, NoMetaData)
      Done
    }
  }

  private def unbind(): Future[Done] = {
    implicit val ec = materializer.executionContext
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
