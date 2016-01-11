/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ IOException, InputStream }
import java.util.concurrent.{ BlockingQueue, LinkedBlockingDeque, TimeUnit }
import akka.stream.Attributes.InputBuffer
import akka.stream.impl.io.InputStreamSinkStage._
import akka.stream.stage._
import akka.util.ByteString
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import akka.stream.{ Inlet, SinkShape, Attributes }

private[akka] object InputStreamSinkStage {

  sealed trait AdapterToStageMessage
  case object ReadElementAcknowledgement extends AdapterToStageMessage
  case object Close extends AdapterToStageMessage

  sealed trait StreamToAdapterMessage
  case class Data(data: ByteString) extends StreamToAdapterMessage
  case object Finished extends StreamToAdapterMessage
  case class Failed(cause: Throwable) extends StreamToAdapterMessage

  sealed trait StageWithCallback {
    def wakeUp(msg: AdapterToStageMessage): Unit
  }
}

/**
 * INTERNAL API
 */
private[akka] class InputStreamSinkStage(readTimeout: FiniteDuration) extends GraphStageWithMaterializedValue[SinkShape[ByteString], InputStream] {

  val in = Inlet[ByteString]("InputStreamSink.in")
  override val shape: SinkShape[ByteString] = SinkShape.of(in)

  // has to be in this order as module depends on shape
  val maxBuffer = module.attributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max
  require(maxBuffer > 0, "Buffer size must be greater than 0")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, InputStream) = {
    val dataQueue = new LinkedBlockingDeque[StreamToAdapterMessage](maxBuffer + 1)

    val logic = new GraphStageLogic(shape) with StageWithCallback {
      var pullRequestIsSent = true

      private val callback: AsyncCallback[AdapterToStageMessage] =
        getAsyncCallback {
          case ReadElementAcknowledgement ⇒ sendPullIfAllowed()
          case Close                      ⇒ completeStage()
        }

      override def wakeUp(msg: AdapterToStageMessage): Unit = callback.invoke(msg)

      private def sendPullIfAllowed(): Unit =
        if (!pullRequestIsSent) {
          pullRequestIsSent = true
          pull(in)
        }

      override def preStart() = pull(in)

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          //1 is buffer for Finished or Failed callback
          require(dataQueue.remainingCapacity() > 1)
          pullRequestIsSent = false
          dataQueue.add(Data(grab(in)))
          if (dataQueue.remainingCapacity() > 1) sendPullIfAllowed()
        }
        override def onUpstreamFinish(): Unit = {
          dataQueue.add(Finished)
          completeStage()
        }
        override def onUpstreamFailure(ex: Throwable): Unit = {
          dataQueue.add(Failed(ex))
          failStage(ex)
        }
      })
    }
    (logic, new InputStreamAdapter(dataQueue, logic.wakeUp, readTimeout))
  }
}

/**
 * INTERNAL API
 * InputStreamAdapter that interacts with InputStreamSinkStage
 */
private[akka] class InputStreamAdapter(sharedBuffer: BlockingQueue[StreamToAdapterMessage],
                                       sendToStage: (AdapterToStageMessage) ⇒ Unit,
                                       readTimeout: FiniteDuration)
  extends InputStream {

  var isActive = true
  var isStageAlive = true
  val subscriberClosedException = new IOException("Reactive stream is terminated, no reads are possible")
  var detachedChunk: Option[ByteString] = None

  @scala.throws(classOf[IOException])
  private[this] def executeIfNotClosed[T](f: () ⇒ T): T =
    if (isActive) f()
    else throw subscriberClosedException

  @scala.throws(classOf[IOException])
  override def read(): Int = {
    val a = Array[Byte](1)
    if (read(a, 0, 1) != -1) a(0)
    else -1
  }

  @scala.throws(classOf[IOException])
  override def read(a: Array[Byte]): Int = read(a, 0, a.length)

  @scala.throws(classOf[IOException])
  override def read(a: Array[Byte], begin: Int, length: Int): Int = {
    require(a.length > 0, "array size must be >= 0")
    require(begin >= 0, "begin must be >= 0")
    require(length > 0, "length must be > 0")
    require(begin + length <= a.length, "begin + length must be smaller or equal to the array length")

    executeIfNotClosed(() ⇒
      if (isStageAlive) {
        detachedChunk match {
          case None ⇒
            try {
              sharedBuffer.poll(readTimeout.toMillis, TimeUnit.MILLISECONDS) match {
                case Data(data) ⇒
                  detachedChunk = Some(data)
                  readBytes(a, begin, length)
                case Finished ⇒
                  isStageAlive = false
                  -1
                case Failed(ex) ⇒
                  isStageAlive = false
                  throw new IOException(ex)
                case null ⇒ throw new IOException("Timeout on waiting for new data")
              }
            } catch {
              case ex: InterruptedException ⇒ throw new IOException(ex)
            }
          case Some(data) ⇒
            readBytes(a, begin, length)
        }
      } else -1)
  }

  private[this] def readBytes(a: Array[Byte], begin: Int, length: Int): Int = {
    require(detachedChunk.nonEmpty, "Chunk must be pulled from shared buffer")
    val availableInChunk = detachedChunk.get.size
    val readBytes = getData(a, begin, length, 0)
    if (readBytes >= availableInChunk) sendToStage(ReadElementAcknowledgement)
    readBytes
  }

  @scala.throws(classOf[IOException])
  override def close(): Unit = {
    executeIfNotClosed(() ⇒ {
      // at this point Subscriber may be already terminated
      if (isStageAlive) sendToStage(Close)
      isActive = false
    })
  }

  @tailrec
  private[this] def getData(arr: Array[Byte], begin: Int, length: Int,
                            gotBytes: Int): Int = {
    grabDataChunk() match {
      case Some(data) ⇒
        val size = data.size
        if (size <= length) {
          data.copyToArray(arr, begin, size)
          detachedChunk = None
          if (size == length)
            gotBytes + size
          else
            getData(arr, begin + size, length - size, gotBytes + size)
        } else {
          data.copyToArray(arr, begin, length)
          detachedChunk = Some(data.drop(length))
          gotBytes + length
        }
      case None ⇒ gotBytes
    }
  }

  private[this] def grabDataChunk(): Option[ByteString] = {
    detachedChunk match {
      case None ⇒
        sharedBuffer.poll() match {
          case Data(data) ⇒
            detachedChunk = Some(data)
            detachedChunk
          case _ ⇒ None
        }
      case Some(_) ⇒ detachedChunk
    }
  }
}

