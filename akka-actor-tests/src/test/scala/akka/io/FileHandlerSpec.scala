package akka.io

import akka.testkit.{ ImplicitSender, TestActorRef, AkkaSpec }
import java.nio.channels.{ OverlappingFileLockException, AsynchronousFileChannel }
import java.nio.file.{ StandardOpenOption, Paths }
import akka.actor.Props
import akka.io.File._
import akka.util.ByteString
import java.nio.ByteBuffer

class FileHandlerSpec extends AkkaSpec with ImplicitSender {
  "A FileHandler" must {
    "be able to write to a file" in new TestSetup {
      ref ! Write(ByteString(testString), 0)

      expectMsg(Written(testString.size))

      val dst = ByteBuffer.allocate(4)
      fileChannel.read(dst, 0).get() should be(4)

      dst.rewind()

      dst.array() should be(testString)
    }

    "be able to read from a file" in new TestSetup {
      fileChannel.write(ByteBuffer.wrap(testString), 0).get() should be(4)

      ref ! Read("test".getBytes.size, 0)

      expectMsg(ReadResult(ByteString(testString), testString.size))
    }

    "be able to read from a specific position in a file" in new TestSetup {
      fileChannel.write(ByteBuffer.wrap(testString ++ "foo".getBytes), 0).get()

      ref ! Read(3, testString.size)

      expectMsg(ReadResult(ByteString("foo"), 3))
    }

    "be able to get the size of a file" in new TestSetup {
      fileChannel.write(ByteBuffer.wrap(testString), 0).get()

      ref ! GetSize

      expectMsg(Size(4))
    }

    "be able to force write to disk" in new TestSetup {
      fileChannel.write(ByteBuffer.wrap(testString), 0).get()

      ref ! Force(true)

      expectMsg(Forced)
    }

    "be able to truncate a file" in new TestSetup {
      fileChannel.write(ByteBuffer.wrap(testString), 0).get()

      ref ! Truncate(2)

      expectMsg(Truncated)

      ref ! GetSize

      expectMsg(Size(2))
    }

    "be able to lock a file" in new TestSetup {
      ref ! Lock

      expectMsg(Locked)

      ref.underlyingActor.lock.isDefined should be(true)

      intercept[OverlappingFileLockException] {
        fileChannel.lock().get()
      }
    }

    "be able to unlock a file" in new TestSetup {
      val lock = fileChannel.lock().get()
      ref.underlyingActor.lock = Some(lock)

      ref ! Unlock

      expectMsg(Unlocked)

      ref.underlyingActor.lock should be(None)

      lock.isValid should be(false)
    }

    "be able to close a file" in new TestSetup {
      ref ! Close

      watch(ref)

      expectMsg(Closed)

      expectTerminated(ref)

      fileChannel.isOpen should be(false)
    }
  }

  class TestSetup {
    val testString = "test".getBytes
    val fileChannel = AsynchronousFileChannel.open(Paths.get("/tmp", "akka-test-file.txt"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE)
    val ref = TestActorRef[FileHandler](Props(classOf[FileHandler], fileChannel))
  }
}
