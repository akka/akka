/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.coding

import java.lang.reflect.{ Method, InvocationTargetException }
import java.util.zip.{ Inflater, Deflater }
import akka.stream.stage._
import akka.util.{ ByteStringBuilder, ByteString }

import scala.annotation.tailrec
import akka.http.impl.util._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings

import scala.util.control.NonFatal

class Deflate(val messageFilter: HttpMessage ⇒ Boolean) extends Coder with StreamDecoder {
  val encoding = HttpEncodings.deflate
  def newCompressor = new DeflateCompressor
  def newDecompressorStage(maxBytesPerChunk: Int) = () ⇒ new DeflateDecompressor(maxBytesPerChunk)
}
object Deflate extends Deflate(Encoder.DefaultFilter)

class DeflateCompressor extends Compressor {
  import DeflateCompressor._

  protected lazy val deflater = new Deflater(Deflater.BEST_COMPRESSION, false)

  override final def compressAndFlush(input: ByteString): ByteString = {
    val buffer = newTempBuffer(input.size)

    compressWithBuffer(input, buffer) ++ flushWithBuffer(buffer)
  }
  override final def compressAndFinish(input: ByteString): ByteString = {
    val buffer = newTempBuffer(input.size)

    compressWithBuffer(input, buffer) ++ finishWithBuffer(buffer)
  }
  override final def compress(input: ByteString): ByteString = compressWithBuffer(input, newTempBuffer())
  override final def flush(): ByteString = flushWithBuffer(newTempBuffer())
  override final def finish(): ByteString = finishWithBuffer(newTempBuffer())

  protected def compressWithBuffer(input: ByteString, buffer: Array[Byte]): ByteString = {
    require(deflater.needsInput())
    deflater.setInput(input.toArray)
    drainDeflater(deflater, buffer)
  }
  protected def flushWithBuffer(buffer: Array[Byte]): ByteString = DeflateCompressor.flush(deflater, buffer)
  protected def finishWithBuffer(buffer: Array[Byte]): ByteString = {
    deflater.finish()
    val res = drainDeflater(deflater, buffer)
    deflater.end()
    res
  }

  private def newTempBuffer(size: Int = 65536): Array[Byte] =
    // The default size is somewhat arbitrary, we'd like to guess a better value but Deflater/zlib
    // is buffering in an unpredictable manner.
    // `compress` will only return any data if the buffered compressed data has some size in
    // the region of 10000-50000 bytes.
    // `flush` and `finish` will return any size depending on the previous input.
    // This value will hopefully provide a good compromise between memory churn and
    // excessive fragmentation of ByteStrings.
    new Array[Byte](size)
}

private[http] object DeflateCompressor {
  // TODO: remove reflective call once Java 6 support is dropped
  /**
   * Compatibility mode: reflectively call deflate(..., flushMode) if available or use a hack otherwise
   */
  private[this] val flushImplementation: (Deflater, Array[Byte]) ⇒ ByteString = {
    def flushHack(deflater: Deflater, buffer: Array[Byte]): ByteString = {
      // hack: change compression mode to provoke flushing
      deflater.deflate(EmptyByteArray, 0, 0)
      deflater.setLevel(Deflater.NO_COMPRESSION)
      val res1 = drainDeflater(deflater, buffer)
      deflater.setLevel(Deflater.BEST_COMPRESSION)
      val res2 = drainDeflater(deflater, buffer)
      res1 ++ res2
    }
    def reflectiveDeflateWithSyncMode(method: Method, syncFlushConstant: Int)(deflater: Deflater, buffer: Array[Byte]): ByteString =
      try {
        val written = method.invoke(deflater, buffer, 0: java.lang.Integer, buffer.length: java.lang.Integer, syncFlushConstant: java.lang.Integer).asInstanceOf[Int]
        ByteString.fromArray(buffer, 0, written)
      } catch {
        case t: InvocationTargetException ⇒ throw t.getTargetException
      }

    try {
      val deflateWithFlush = classOf[Deflater].getMethod("deflate", classOf[Array[Byte]], classOf[Int], classOf[Int], classOf[Int])
      require(deflateWithFlush.getReturnType == classOf[Int])
      val flushModeSync = classOf[Deflater].getField("SYNC_FLUSH").get(null).asInstanceOf[Int]
      reflectiveDeflateWithSyncMode(deflateWithFlush, flushModeSync)
    } catch {
      case NonFatal(e) ⇒ flushHack
    }
  }

  def flush(deflater: Deflater, buffer: Array[Byte]): ByteString = flushImplementation(deflater, buffer)

  @tailrec
  def drainDeflater(deflater: Deflater, buffer: Array[Byte], result: ByteStringBuilder = new ByteStringBuilder()): ByteString = {
    val len = deflater.deflate(buffer)
    if (len > 0) {
      result ++= ByteString.fromArray(buffer, 0, len)
      drainDeflater(deflater, buffer, result)
    } else {
      require(deflater.needsInput())
      result.result()
    }
  }
}

class DeflateDecompressor(maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault) extends DeflateDecompressorBase(maxBytesPerChunk) {
  protected def createInflater() = new Inflater()

  def initial: State = StartInflate
  def afterInflate: State = StartInflate

  protected def afterBytesRead(buffer: Array[Byte], offset: Int, length: Int): Unit = {}
  protected def onTruncation(ctx: Context[ByteString]): SyncDirective = ctx.finish()
}

abstract class DeflateDecompressorBase(maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault) extends ByteStringParserStage[ByteString] {
  protected def createInflater(): Inflater
  val inflater = createInflater()

  protected def afterInflate: State
  protected def afterBytesRead(buffer: Array[Byte], offset: Int, length: Int): Unit

  /** Start inflating */
  case object StartInflate extends IntermediateState {
    def onPush(data: ByteString, ctx: Context[ByteString]): SyncDirective = {
      require(inflater.needsInput())
      inflater.setInput(data.toArray)

      becomeWithRemaining(Inflate()(data), ByteString.empty, ctx)
    }
  }

  /** Inflate */
  case class Inflate()(data: ByteString) extends IntermediateState {
    override def onPull(ctx: Context[ByteString]): SyncDirective = {
      val buffer = new Array[Byte](maxBytesPerChunk)
      val read = inflater.inflate(buffer)
      if (read > 0) {
        afterBytesRead(buffer, 0, read)
        ctx.push(ByteString.fromArray(buffer, 0, read))
      } else {
        val remaining = data.takeRight(inflater.getRemaining)
        val next =
          if (inflater.finished()) afterInflate
          else StartInflate

        becomeWithRemaining(next, remaining, ctx)
      }
    }
    def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective =
      throw new IllegalStateException("Don't expect a new Element")
  }

  def becomeWithRemaining(next: State, remaining: ByteString, ctx: Context[ByteString]) = {
    become(next)
    if (remaining.isEmpty) current.onPull(ctx)
    else current.onPush(remaining, ctx)
  }
}
