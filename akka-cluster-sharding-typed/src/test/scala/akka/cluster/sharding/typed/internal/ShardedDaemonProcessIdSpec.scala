/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.cluster.sharding.typed.internal.ShardedDaemonProcessId.DecodedId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ShardedDaemonProcessIdSpec extends AnyWordSpecLike with Matchers {

  "The sharded daemon process ids" should {

    "decode single int in id as id for non-rescaling" in {
      // number of processes and revision not used so 0 in this case
      ShardedDaemonProcessId.decodeEntityId("1", supportsRescale = false) should ===(DecodedId(0L, 0, 1))
    }

    "decode composed id as id for rescaling" in {
      ShardedDaemonProcessId.decodeEntityId("1|4|3", supportsRescale = true) should ===(DecodedId(1L, 4, 3))
    }

    "decode single int in id as id for rescaling (to support rolling upgrade)" in {
      // number of processes and revision not used so 0 in this case
      ShardedDaemonProcessId.decodeEntityId("1", supportsRescale = true) should ===(DecodedId(-1L, -1, 1))
    }

    "spread out workers over shards for non-rescaling" in {
      ShardedDaemonProcessId.allShardsFor(1, 8, supportsRescale = false).size should ===(8)
    }

    "spread out workers over shards for rescaling" in {
      ShardedDaemonProcessId.allShardsFor(1, 8, supportsRescale = true).size should ===(8)
    }

    "extract old id as shard in message extractor (to support rolling upgrade)" in {
      val extractor = new ShardedDaemonProcessId.MessageExtractor[Any](supportsRescale = true)
      extractor.shardId("1") should ===("1")
    }
  }

}
