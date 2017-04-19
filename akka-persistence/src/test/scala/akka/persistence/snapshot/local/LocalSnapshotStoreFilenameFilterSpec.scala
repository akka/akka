/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.snapshot.local

import java.io.File
import java.net.URLEncoder
import java.util.Date

import akka.persistence.SnapshotMetadata
import org.scalatest.{ Matchers, WordSpec }

class LocalSnapshotStoreFilenameFilterSpec extends WordSpec with Matchers {
  import LocalSnapshotStore._

  val root = new File(".")

  "LocalSnapshotStore.SnapshotSeqNrFilenameFilter" must {
    final case class TestCase(pid: String, accept: String, reject: List[String])
    // note that this filter does not validate the seq nr being a proper integer!
    // this is handled in `SnapshotSeqNrFilenameFilter`

    val t = (new Date).getTime
    val seq = 1337
    val cases = List(
      TestCase(
        s"simple",
        accept = s"snapshot-simple-$seq-$t", // will get URL encoded before passing in
        reject = List(s"snapshot-simple-$seq-$t-wat")),
      TestCase(
        s"simple",
        accept = s"snapshot-simple-abc-xyz", // suprising but true, this filter just looks for -simple-, not validating the numbers
        reject = List()),
      TestCase(
        s"with-dashes",
        accept = s"snapshot-with-dashes-$seq-$t", // will get URL encoded before passing in
        reject = List(s"snapshot-with-dashes-$seq-$t-wat")),
      TestCase(
        s"numbers_23121",
        accept = s"snapshot-numbers_23121-$seq-$t", // will get URL encoded before passing in
        reject = List(s"snapshot-numbers_23121-$seq-$t-wat"))
    // TODO add and re-think about names with spaces
    )

    cases foreach { test ⇒
      s"accept [${URLEncoder.encode(test.accept)}] for persistenceId: [${test.pid}]" in {
        val filter = new SnapshotFilenameFilter(test.pid)
        filter.accept(root, test.accept) should ===(true)
      }

      test.reject foreach { reject ⇒
        s"reject [$reject] for persistenceId: [${test.pid}]" in {
          val filter = new SnapshotFilenameFilter(test.pid)
          filter.accept(root, reject) should ===(false)
        }
      }
    }
  }
  "LocalSnapshotStore.SnapshotFilenameFilter" must {
    final case class TestCase(meta: SnapshotMetadata, accept: String, reject: List[String])
    // note that this filter does not validate the seq nr being a proper integer!
    // this is handled in `SnapshotSeqNrFilenameFilter`

    val t = (new Date).getTime
    val seq = 1337
    val cases = List(
      TestCase(
        SnapshotMetadata("simple", seq, t),
        accept = s"snapshot-simple-$seq-$t", // will get URL encoded before passing in
        reject = List(s"snapshot-simple-$seq-$t-wat")),
      TestCase(
        SnapshotMetadata("with-dashes", seq, t),
        accept = s"snapshot-with-dashes-$seq-$t", // will get URL encoded before passing in
        reject = List(s"snapshot-with-dashes-$seq-$t-wat")),
      TestCase(
        SnapshotMetadata("numbers_23121", seq, t),
        accept = s"snapshot-numbers_23121-$seq-$t", // will get URL encoded before passing in
        reject = List(s"snapshot-numbers_23121-$seq-$t-wat"))
    // TODO add and re-think about names with spaces
    )

    cases foreach { test ⇒
      s"accept [${test.accept}] for meta: [${test.meta}]" in {
        val filter = new SnapshotSeqNrFilenameFilter(test.meta)
        filter.accept(root, test.accept) should ===(true)
      }

      test.reject foreach { reject ⇒
        s"reject [$reject] for persistenceId: [${test.meta}]" in {
          val filter = new SnapshotSeqNrFilenameFilter(test.meta)
          filter.accept(root, reject) should ===(false)
        }
      }
    }
  }

}
