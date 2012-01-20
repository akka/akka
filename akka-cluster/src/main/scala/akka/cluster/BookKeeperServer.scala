/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import org.apache.bookkeeper.proto.BookieServer

import java.io.File

/*
A simple use of BookKeeper is to implement a write-ahead transaction log. A server maintains an in-memory data structure
(with periodic snapshots for example) and logs changes to that structure before it applies the change. The system
server creates a ledger at startup and store the ledger id and password in a well known place (ZooKeeper maybe). When
it needs to make a change, the server adds an entry with the change information to a ledger and apply the change when
BookKeeper adds the entry successfully. The server can even use asyncAddEntry to queue up many changes for high change
throughput. BooKeeper meticulously logs the changes in order and call the completion functions in order.

When the system server dies, a backup server will come online, get the last snapshot and then it will open the
ledger of the old server and read all the entries from the time the snapshot was taken. (Since it doesn't know the last
entry number it will use MAX_INTEGER). Once all the entries have been processed, it will close the ledger and start a
new one for its use.
*/

object BookKeeperServer {
  val port = 3181
  val zkServers = "localhost:2181"
  val journal = new File("./bk/journal")
  val ledgers = Array(new File("./bk/ledger"))
  val bookie = new BookieServer(port, zkServers, journal, ledgers)

  def start() {
    bookie.start()
    bookie.join()
  }
}
