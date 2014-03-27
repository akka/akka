/**
 *  Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import java.io.{ FileOutputStream, File }
import org.scalatest.{ Matchers, WordSpec }

class BytesSpec extends WordSpec with Matchers {
  import BytesUtils._

  "Bytes" must {
    "properly support `slice`" when {
      "ByteString" in {
        ByteString("Ken sent me!").slice(4L, 3L) shouldBe ByteString("sen")
      }
      "FileBytes" in {
        val file = File.createTempFile("akka-util_BytesSpec", ".txt")
        try {
          writeAllText(" Ken sent me!", file)
          Bytes(file, 1).slice(4L, 3L) shouldBe Bytes(file, 5, 3)
        } finally file.delete
      }
      "ByteStrings.slice" in {
        val data = ByteString("Ken") ++ ByteString(" sent ") ++ ByteString("me") ++ ByteString("!")
        data.slice(2L, 5L) shouldBe ByteString("n sen")
      }
    }
    "properly support `chunk`" when {
      "ByteString" in {
        ByteString("Ken sent me!").chunk(5) shouldBe Stream(
          ByteString("Ken s"),
          ByteString("ent m"),
          ByteString("e!"))
      }
      "FileBytes" in {
        withFileBytes("Ken sent me!") { fb ⇒
          fb.chunk(5) shouldBe Stream(
            fb.slice(0, 5),
            fb.slice(5, 5),
            fb.slice(10, 2))
        }
      }
      "ByteStrings.chunk" in {
        val data = ByteString("Ken") ++ ByteString(" sent ") ++ ByteString("me!")
        data.chunk(5) shouldBe Stream(
          ByteString("Ken s"),
          ByteString("ent m"),
          ByteString("e!"))
      }
    }
  }
}

object BytesUtils {
  def withFileBytes[T](text: String = "Ken sent me!")(f: Bytes ⇒ T): T = {
    val file = File.createTempFile("akka-util_BytesSpec", ".txt")
    try {
      writeAllText(text, file)
      f(Bytes(file))
    } finally file.delete
  }
  def virtualFileBytes(length: Int): FileBytes =
    // using private[util] constructor, file doesn't really have to exist
    FileBytes("/tmp/dummy", 0, length)

  def writeAllText(text: String, file: File) = {
    val fos = new FileOutputStream(file)
    try fos.write(text.getBytes("utf8"))
    finally fos.close()
  }
}