package akka.io

import akka.actor._
import java.nio.file.{ OpenOption, Path }
import java.nio.file.attribute.FileAttribute
import com.typesafe.config.Config
import java.nio.channels.{ FileLock, CompletionHandler, AsynchronousFileChannel }
import akka.util.ByteString
import java.io.IOException
import java.nio.ByteBuffer

object File extends ExtensionId[FileExt] with ExtensionIdProvider {

  override def lookup() = File

  override def createExtension(system: ExtendedActorSystem): FileExt = new FileExt(system)

  // commands
  trait Command

  case class Open(file: Path, openOptions: Seq[_ <: OpenOption] = Nil, fileAttributes: Seq[FileAttribute[_]] = Nil) extends Command

  case class Write(bytes: ByteString, position: Long) extends Command

  case class Read(size: Int, position: Long) extends Command

  case object GetSize extends Command

  case class Force(metaData: Boolean) extends Command

  case class Truncate(size: Long) extends Command

  case object Lock extends Command

  case object Unlock extends Command

  case object Close extends Command

  // events
  trait Event

  case class Opened(handler: ActorRef) extends Event

  case class Size(size: Long) extends Event

  case class Written(bytesWritten: Int) extends Event

  case class ReadResult(bytes: ByteString, bytesRead: Int) extends Event

  case class CommandFailed(cmd: Command, cause: Throwable) extends Event

  case object Forced extends Event

  case object Truncated extends Event

  case object Locked extends Event

  case object Unlocked extends Event

  case object Closed extends Event
}

class FileExt(system: ExtendedActorSystem) extends IO.Extension {
  val manager: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(classOf[FileManager], this).withDeploy(Deploy.local),
      name = "IO-FILE")
  }
}

class FileManager extends Actor {
  import File._
  import scala.collection.JavaConverters._

  override def receive = {
    case cmd @ Open(file, openOptions, fileAttributes) ⇒
      try {
        val channel = AsynchronousFileChannel.open(file, openOptions.toSet.asJava, null, fileAttributes: _*)
        val ref = context.actorOf(Props(classOf[FileHandler], channel))
        sender() ! Opened(ref)
      } catch {
        case e: Exception ⇒ sender() ! CommandFailed(cmd, e)
      }
  }
}

class FileHandler(channel: AsynchronousFileChannel) extends Actor {
  import File._
  import FileHandler._

  var lock: Option[FileLock] = None

  override def receive = {
    case cmd @ Write(bytes, position) ⇒
      channel.write[AnyRef](bytes.asByteBuffer, position, null, new WriteCompletionHandler(sender(), cmd))

    case cmd @ Read(size, position) ⇒
      val dst = ByteBuffer.allocate(size)
      channel.read[AnyRef](dst, position, null, new ReadCompletionHandler(sender(), dst, cmd))

    case GetSize ⇒
      try {
        sender() ! Size(channel.size())
      } catch {
        case e: Exception ⇒ sender() ! CommandFailed(GetSize, e)
      }

    case cmd @ Force(metaData) ⇒
      try {
        channel.force(metaData)
        sender() ! Forced
      } catch {
        case e: Exception ⇒ sender ! CommandFailed(cmd, e)
      }

    case cmd @ Truncate(size) ⇒
      try {
        channel.truncate(size)
        sender() ! Truncated
      } catch {
        case e: Exception ⇒ sender() ! CommandFailed(cmd, e)
      }

    case Lock ⇒ channel.lock[AnyRef](null, new LockCompletionHandler(self, sender(), Lock))

    case Unlock ⇒
      lock.foreach(_.release())
      lock = None
      sender() ! Unlocked

    case FileLockAcquired(_lock) ⇒ lock = Some(_lock)

    case Close ⇒
      context.stop(self)
      sender ! Closed
  }

  override def postStop = channel.close()

  private[this] sealed trait BasicCompletionHandler[A, B] extends CompletionHandler[A, B] {
    def receiver: ActorRef
    def cmd: Command

    override def failed(exc: Throwable, attachment: B): Unit = receiver ! CommandFailed(cmd, exc)
  }

  private[this] class WriteCompletionHandler(val receiver: ActorRef, val cmd: Command) extends BasicCompletionHandler[Integer, AnyRef] {
    override def completed(result: Integer, attachment: AnyRef): Unit = receiver ! Written(result.intValue())
  }

  private[this] class ReadCompletionHandler(val receiver: ActorRef, dst: ByteBuffer, val cmd: Command) extends BasicCompletionHandler[Integer, AnyRef] {
    override def completed(result: Integer, attachment: AnyRef): Unit = {
      dst.rewind()
      receiver ! ReadResult(ByteString(dst), result.intValue())
    }
  }

  private[this] class LockCompletionHandler(fileHandler: ActorRef, val receiver: ActorRef, val cmd: Command) extends BasicCompletionHandler[FileLock, AnyRef] {
    override def completed(result: FileLock, attachment: AnyRef): Unit = {
      fileHandler ! FileLockAcquired(result)
      receiver ! Locked
    }
  }
}

object FileHandler {
  private[FileHandler] case class FileLockAcquired(lock: FileLock) extends NoSerializationVerificationNeeded
}
