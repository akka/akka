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
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import akka.stream.Attributes

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
private[akka] class InputStreamSinkStage(timeout: FiniteDuration) extends SinkStage[ByteString, InputStream]("InputStreamSinkStage") {
  val maxBuffer = module.attributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max
  require(maxBuffer > 0, "Buffer size must be greater than 0")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, InputStream) = {

    val dataQueue = new LinkedBlockingDeque[StreamToAdapterMessage](maxBuffer + 1)
    var pullRequestIsSent = true

    val logic = new GraphStageLogic(shape) with StageWithCallback {

      private val callback: AsyncCallback[AdapterToStageMessage] =
        getAsyncCallback(onAsyncMessage)

      override def wakeUp(msg: AdapterToStageMessage): Unit = {
        if (!isClosed(in)) {
          Future(callback.invoke(msg))(interpreter.materializer.executionContext)
        }
      }

      private def onAsyncMessage(event: AdapterToStageMessage): Unit =
        event match {
          case ReadElementAcknowledgement ⇒
            sendPullIfAllowed()
          case Close ⇒
            completeStage()
        }

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
    (logic, new InputStreamAdapter(dataQueue, logic.wakeUp, timeout))
  }
}

/**
 * INTERNAL API
 * InputStreamAdapter that interacts with InputStreamSinkStage
 */
private[akka] class InputStreamAdapter(sharedBuffer: BlockingQueue[StreamToAdapterMessage],
                                       sendToStage: (AdapterToStageMessage) ⇒ Unit,
                                       timeout: FiniteDuration)
  extends InputStream {

  var isActive = true
  var isStageAlive = true
  val subscriberClosedException = new IOException("Reactive stream is terminated, no reads are possible")
  var skipBytes = 0
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
    executeIfNotClosed(() ⇒
      if (isStageAlive) {
        detachedChunk match {
          case None ⇒
            sharedBuffer.poll(timeout.toMillis, TimeUnit.MILLISECONDS) match {
              case Data(data) ⇒
                detachedChunk = Some(data)
                readBytes(a, begin, length)
              case Finished ⇒
                isStageAlive = false
                -1
              case Failed(ex) ⇒
                isStageAlive = false
                throw new IOException(ex)
            }
          case Some(data) ⇒
            readBytes(a, begin, length)
        }
      } else -1)
  }

  private[this] def readBytes(a: Array[Byte], begin: Int, length: Int): Int = {
    val availableInChunk = detachedChunk.size - skipBytes
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
    getDataChunk() match {
      case Some(data) ⇒
        val size = data.size - skipBytes
        if (size + gotBytes <= length) {
          System.arraycopy(data.toArray, skipBytes, arr, begin, size)
          skipBytes = 0
          detachedChunk = None
          if (length - size == 0)
            gotBytes + size
          else
            getData(arr, begin + size, length - size, gotBytes + size)
        } else {
          System.arraycopy(data.toArray, skipBytes, arr, begin, length)
          skipBytes = length
          gotBytes + length
        }
      case None ⇒ gotBytes
    }
  }

  private[this] def getDataChunk(): Option[ByteString] = {
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

