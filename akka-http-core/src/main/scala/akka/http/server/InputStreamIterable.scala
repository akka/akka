package akka.http.server

import java.io.{FileInputStream, InputStream, File}
import akka.util.ByteString

trait StatefulIterator[T] extends Iterator[T] {
  type State = Iterator[T]
  var state: State = initial

  def initial: State
  def become(next: State): Unit = state = next

  override def next(): T = state.next()
  override def hasNext: Boolean = state.hasNext
}

class FileIterable(file: File, val blockSize: Int = 100000) extends InputStreamIterable {
  override def createStream(): InputStream = new FileInputStream(file)
}

trait InputStreamIterable extends Iterable[ByteString] {
  def createStream(): InputStream
  def blockSize: Int

  override def iterator: Iterator[ByteString] = new StatefulIterator[ByteString] {
    val buffer = new Array[Byte](blockSize)
    val is = createStream()

    def initial = NothingRead
    lazy val NothingRead: State = new State {
      override def hasNext: Boolean = {
        val read = is.read(buffer)
        if (read == -1) {
          become(Completed)
          false
        } else {
          become(Buffered(ByteString.fromArray(buffer, 0, read)))
          true
        }
      }
      override def next(): ByteString = {
        val read = is.read(buffer)
        if (read == -1) {
          become(Completed)
          throw new NoSuchElementException
        } else ByteString.fromArray(buffer, 0, read)
      }
    }
    def Buffered(buffer: ByteString): State = new State {
      override def hasNext: Boolean = true
      override def next(): ByteString = {
        become(NothingRead)
        buffer
      }
    }
    lazy val Completed: State = new State {
      override def next(): ByteString = throw new NoSuchElementException
      override def hasNext: Boolean = false
    }
  }
}