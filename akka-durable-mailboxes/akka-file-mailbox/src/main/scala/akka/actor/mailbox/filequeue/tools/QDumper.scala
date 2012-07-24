/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.actor.mailbox.filequeue.tools

import language.reflectiveCalls

import java.io.{ FileNotFoundException, IOException }
import scala.collection.mutable
import akka.actor.mailbox.filequeue._
import akka.event.LoggingAdapter
import akka.actor.ActorSystem

class QueueDumper(filename: String, log: LoggingAdapter) {
  var offset = 0L
  var operations = 0L
  var currentXid = 0

  val queue = new mutable.Queue[Int] {
    def unget(item: Int) = prependElem(item)
  }
  val openTransactions = new mutable.HashMap[Int, Int]

  def apply() {
    val journal = new Journal(filename, false, log)
    var lastDisplay = 0L

    try {
      for ((item, itemsize) ← journal.walk()) {
        operations += 1
        dumpItem(item)
        offset += itemsize
        if (QDumper.quiet && offset - lastDisplay > 1024 * 1024) {
          print("\rReading journal: %-6s".format(Util.bytesToHuman(offset, 0)))
          Console.flush()
          lastDisplay = offset
        }
      }
      print("\r" + (" " * 30))

      println()
      val totalItems = queue.size + openTransactions.size
      val totalBytes = queue.foldLeft(0L) { _ + _ } + openTransactions.values.foldLeft(0L) { _ + _ }
      println("Journal size: %d bytes, with %d operations.".format(offset, operations))
      println("%d items totalling %d bytes.".format(totalItems, totalBytes))
    } catch {
      case e: FileNotFoundException ⇒
        println("Can't open journal file: " + filename)
      case e: IOException ⇒
        println("Exception reading journal file: " + filename)
        e.printStackTrace()
    }
  }

  def dumpItem(item: JournalItem) {
    val now = System.currentTimeMillis
    if (!QDumper.quiet) print("%08x  ".format(offset & 0xffffffffL))
    item match {
      case JournalItem.Add(qitem) ⇒
        if (!QDumper.quiet) {
          print("ADD %-6d".format(qitem.data.size))
          if (qitem.xid > 0) {
            print(" xid=%d".format(qitem.xid))
          }
          if (qitem.expiry > 0) {
            if (qitem.expiry - now < 0) {
              print(" expired")
            } else {
              print(" exp=%d".format(qitem.expiry - now))
            }
          }
          println()
        }
        queue += qitem.data.size
      case JournalItem.Remove ⇒
        if (!QDumper.quiet) println("REM")
        queue.dequeue
      case JournalItem.RemoveTentative ⇒
        do {
          currentXid += 1
        } while (openTransactions contains currentXid)
        openTransactions(currentXid) = queue.dequeue
        if (!QDumper.quiet) println("RSV %d".format(currentXid))
      case JournalItem.SavedXid(xid) ⇒
        if (!QDumper.quiet) println("XID %d".format(xid))
        currentXid = xid
      case JournalItem.Unremove(xid) ⇒
        queue.unget(openTransactions.remove(xid).get)
        if (!QDumper.quiet) println("CAN %d".format(xid))
      case JournalItem.ConfirmRemove(xid) ⇒
        if (!QDumper.quiet) println("ACK %d".format(xid))
        openTransactions.remove(xid)
      case x ⇒
        if (!QDumper.quiet) println(x)
    }
  }
}

object QDumper {
  val filenames = new mutable.ListBuffer[String]
  var quiet = false

  def usage() {
    println()
    println("usage: qdump.sh <journal-files...>")
    println("    describe the contents of a kestrel journal file")
    println()
    println("options:")
    println("    -q      quiet: don't describe every line, just the summary")
    println()
  }

  def parseArgs(args: List[String]): Unit = args match {
    case Nil ⇒
    case "--help" :: xs ⇒
      usage()
      System.exit(0)
    case "-q" :: xs ⇒
      quiet = true
      parseArgs(xs)
    case x :: xs ⇒
      filenames += x
      parseArgs(xs)
  }

  def main(args: Array[String]) {
    parseArgs(args.toList)
    if (filenames.size == 0) {
      usage()
      System.exit(0)
    }

    val system = ActorSystem()

    for (filename ← filenames) {
      println("Queue: " + filename)
      new QueueDumper(filename, system.log)()
    }

    system.shutdown()
  }
}
