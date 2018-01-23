/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl._

import scala.concurrent.Await
import scala.concurrent.Future

import akka.Done
import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.dispatch.ExecutionContexts
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransportException
import akka.remote.artery.Decoder.InboundCompressionAccess
import akka.remote.artery.compress._
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.Client
import akka.stream.IgnoreComplete
import akka.stream.KillSwitches
import akka.stream.Server
import akka.stream.SinkShape
import akka.stream.TLSProtocol._
import akka.stream.TLSRole
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Partition
import akka.stream.scaladsl.RestartFlow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.TLS
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Tcp.ServerBinding
import akka.util.ByteString

/**
 * INTERNAL API
 */
private[remote] class ArteryTcpTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider,
                                         tlsEnabled: Boolean)
  extends ArteryTransport(_system, _provider) {
  import ArteryTransport.InboundStreamMatValues
  import FlightRecorderEvents._

  private var serverBinding: Option[Future[ServerBinding]] = None

  override protected def startTransport(): Unit = {
    // FIXME new TCP events
    topLevelFREvents.loFreq(Transport_AeronStarted, NoMetaData)
  }

  override protected def outboundTransportSink(
    outboundContext: OutboundContext,
    streamId:        Int,
    bufferPool:      EnvelopeBufferPool): Sink[EnvelopeBuffer, Future[Done]] = {
    implicit val sys = system

    def tcp = Tcp().outgoingConnection(outboundContext.remoteAddress.host.get, outboundContext.remoteAddress.port.get)

    def connectionFlow: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] =
      if (tlsEnabled) {
        Flow[ByteString]
          .map(bytes ⇒ SendBytes(bytes))
          .viaMat(tls(role = Client).joinMat(tcp)(Keep.right))(Keep.right)
          .collect {
            case SessionBytes(_, bytes) ⇒ bytes
          }
      } else
        tcp

    def connectionFlowWithRestart: Flow[ByteString, ByteString, NotUsed] = {
      import scala.concurrent.duration._
      // FIXME config of backoff
      RestartFlow.withBackoff[ByteString, ByteString](1.second, 5.seconds, 0.1) { () ⇒
        connectionFlow.mapMaterializedValue(_ ⇒ NotUsed)
          .log(name = s"outbound connection to [${outboundContext.remoteAddress}]")
          .addAttributes(Attributes.logLevels(onElement = LogLevels.Off))
      }
    }

    Flow[EnvelopeBuffer]
      .map { env ⇒
        val bytes = ByteString(env.byteBuffer)
        bufferPool.release(env)
        bytes
      }
      .recoverWithRetries(1, { case ArteryTransport.ShutdownSignal ⇒ Source.empty })
      // restart of inner connection part only needed in control flow, since system messages
      // are buffered and resent from the outer SystemMessageDelivery stage
      .via(if (streamId == controlStreamId) connectionFlowWithRestart else connectionFlow)
      .map(_ ⇒ throw new IllegalStateException(s"Unexpected incoming bytes in outbound connection to [${outboundContext.remoteAddress}]"))
      .toMat(Sink.ignore)(Keep.right)

    // FIXME The mat value Future is currently never completed, because RestartFlow will retry forever, should it give up?
    //       related to give-up-system-message-after ?

  }

  override protected def runInboundStreams(): Unit = {
    implicit val mat = materializer
    implicit val sys = system

    val controlStream = runInboundControlStream()
    val ordinaryMessagesStream = runInboundOrdinaryMessagesStream()
    val largeMessagesStream =
      if (largeMessageChannelEnabled)
        runInboundLargeMessagesStream()
      else
        Flow[EnvelopeBuffer]
          .map(_ ⇒ log.warning("Dropping large message, missing large-message-destinations configuration."))
          .to(Sink.ignore)

    val inboundStream: Sink[EnvelopeBuffer, NotUsed] =
      Sink.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val partition = b.add(Partition[EnvelopeBuffer](3, env ⇒ {
          env.byteBuffer.get(EnvelopeBuffer.StreamIdOffset).toInt match {
            case `ordinaryStreamId` ⇒ 1
            case `controlStreamId`  ⇒ 0
            case `largeStreamId`    ⇒ 2
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

    val inboundConnectionFlow = Flow[ByteString]
      .via(Framing.lengthField(fieldLength = 4, fieldOffset = EnvelopeBuffer.FrameLengthOffset,
        maxFrameSize, byteOrder = ByteOrder.LITTLE_ENDIAN))
      .map { frame ⇒
        val buffer = ByteBuffer.wrap(frame.toArray)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        new EnvelopeBuffer(buffer)
      }
      .alsoTo(inboundStream)
      .filter(_ ⇒ false) // don't send back anything in this TCP socket
      .map(_ ⇒ ByteString.empty) // make it a Flow[ByteString] again

    serverBinding =
      Some(Tcp().bind(localAddress.address.host.get, localAddress.address.port.get)
        .to(Sink.foreach { connection ⇒
          if (tlsEnabled) {
            val rhs: Flow[SslTlsInbound, SslTlsOutbound, Any] =
              Flow[SslTlsInbound]
                .collect {
                  case SessionBytes(_, bytes) ⇒ bytes
                }
                .via(inboundConnectionFlow)
                .map(SendBytes.apply)

            connection.handleWith(tls(role = Server).reversed join rhs)
          } else
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
  }

  private def runInboundControlStream(): Sink[EnvelopeBuffer, NotUsed] = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (hub, ctrl, completed) =
      MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
        .via(inboundFlow(settings, NoInboundCompressions))
        .toMat(inboundControlSink)({ case (a, (c, d)) ⇒ (a, c, d) })
        .run()(controlMaterializer)
    attachControlMessageObserver(ctrl)
    implicit val ec = materializer.executionContext
    updateStreamMatValues(controlStreamId, completed)
    attachStreamRestart("Inbound control stream", completed, () ⇒ runInboundControlStream())

    hub
  }

  private def runInboundOrdinaryMessagesStream(): Sink[EnvelopeBuffer, NotUsed] = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (inboundHub: Sink[EnvelopeBuffer, NotUsed], inboundCompressionAccess, completed) =
      if (inboundLanes == 1) {
        MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
          .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
          .toMat(inboundSink(envelopeBufferPool))({ case ((a, b), c) ⇒ (a, b, c) })
          .run()(materializer)

      } else {
        // TODO perhaps a few more things can be extracted and DRY with AeronUpdTransport.runInboundOrdinaryMessagesStream
        val hubKillSwitch = KillSwitches.shared("hubKillSwitch")

        val source: Source[InboundEnvelope, (Sink[EnvelopeBuffer, NotUsed], InboundCompressionAccess)] =
          MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
            .via(hubKillSwitch.flow)
            .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
            .via(Flow.fromGraph(new DuplicateHandshakeReq(inboundLanes, this, system, envelopeBufferPool)))

        val (inboundHub, compressionAccess, hub) =
          source
            .toMat(Sink.fromGraph(new FixedSizePartitionHub[InboundEnvelope](inboundLanePartitioner, inboundLanes,
              settings.Advanced.InboundHubBufferSize)))({ case ((a, b), c) ⇒ (a, b, c) })
            .run()(materializer)

        val lane = inboundSink(envelopeBufferPool)
        val completedValues: Vector[Future[Done]] =
          (0 until inboundLanes).map { _ ⇒
            hub.toMat(lane)(Keep.right).run()(materializer)
          }(collection.breakOut)

        import system.dispatcher

        // tear down the upstream hub part if downstream lane fails
        // lanes are not completed with success by themselves so we don't have to care about onSuccess
        Future.firstCompletedOf(completedValues).failed.foreach { reason ⇒ hubKillSwitch.abort(reason) }
        val allCompleted = Future.sequence(completedValues).map(_ ⇒ Done)

        (inboundHub, compressionAccess, allCompleted)
      }

    setInboundCompressionAccess(inboundCompressionAccess)

    updateStreamMatValues(ordinaryStreamId, completed)

    // FIXME restart of inbound is not working, see failing SurviveInboundStreamRestartWithCompressionInFlightSpec
    //       we must restart the whole inbound thing, not this part only
    attachStreamRestart("Inbound message stream", completed, () ⇒ runInboundOrdinaryMessagesStream())

    inboundHub
  }

  private def runInboundLargeMessagesStream(): Sink[EnvelopeBuffer, NotUsed] = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (hub, completed) =
      MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
        .via(inboundLargeFlow(settings))
        .toMat(inboundSink(largeEnvelopeBufferPool))(Keep.both)
        .run()(materializer)

    updateStreamMatValues(largeStreamId, completed)
    attachStreamRestart("Inbound large message stream", completed, () ⇒ runInboundLargeMessagesStream())

    hub
  }

  private def updateStreamMatValues(streamId: Int, completed: Future[Done]): Unit = {
    implicit val ec = materializer.executionContext
    updateStreamMatValues(controlStreamId, InboundStreamMatValues(
      None,
      completed.recover { case _ ⇒ Done }))
  }

  override protected def shutdownTransport(): Future[Done] = {
    implicit val ec = materializer.executionContext
    serverBinding match {
      case Some(binding) ⇒
        for {
          b ← binding
          _ ← b.unbind()
        } yield {
          topLevelFREvents.loFreq(Transport_Stopped, NoMetaData)
          Done
        }
      case None ⇒
        Future.successful(Done)
    }
  }

  // FIXME proper SslConfig

  def initWithTrust(trustPath: String) = {
    val password = "changeme"

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(getClass.getResourceAsStream("/keystore"), password.toCharArray)

    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
    trustStore.load(getClass.getResourceAsStream(trustPath), password.toCharArray)

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, password.toCharArray)

    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(trustStore)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  def initSslContext(): SSLContext = initWithTrust("/truststore")

  lazy val sslContext = initSslContext()
  lazy val cipherSuites = NegotiateNewSession.withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA")

  def tls(role: TLSRole) = TLS(sslContext, None, cipherSuites, role, IgnoreComplete)

}
