/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka

package object persistence {
  implicit val snapshotMetadataOrdering = new Ordering[SnapshotMetadata] {
    def compare(x: SnapshotMetadata, y: SnapshotMetadata) =
      if (x.processorId == y.processorId) math.signum(x.sequenceNr - y.sequenceNr).toInt
      else x.processorId.compareTo(y.processorId)
  }
}
