/*
 * toPositive and murmur based on Java version in https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/utils/Utils.java
 * to match up with Kafka partitioning. Licensed under:
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.cluster.sharding.typed.internal

import java.nio.charset.StandardCharsets

import akka.annotation.InternalApi

/** INTERNAL API */
@InternalApi
private[sharding] object Murmur2 {
  def toPositive(number: Int): Int = number & 0x7fffffff
  def murmur2(data: Array[Byte]) = {
    val length = data.length
    val seed = 0x9747b28c
    // 'm' and 'r' are mixing constants generated offline.
    // They're not really 'magic', they just happen to work well.
    val m = 0x5bd1e995
    val r = 24
    // Initialize the hash to a random value
    var h = seed ^ length
    val length4 = length / 4
    for (i <- 0 until length4) {
      val i4 = i * 4
      var k = (data(i4 + 0) & 0xff) + ((data(i4 + 1) & 0xff) << 8) + ((data(i4 + 2) & 0xff) << 16) + ((data(
        i4 + 3) & 0xff) << 24)
      k *= m
      k ^= k >>> r
      k *= m
      h *= m
      h ^= k
    }
    // Handle the last few bytes of the input array
    length % 4 match {
      case 3 =>
        h ^= (data((length & ~3) + 2) & 0xff) << 16
        h ^= (data((length & ~3) + 1) & 0xff) << 8
        h ^= data(length & ~3) & 0xff
        h *= m
      case 2 =>
        h ^= (data((length & ~3) + 1) & 0xff) << 8
        h ^= data(length & ~3) & 0xff
        h *= m
      case 1 =>
        h ^= data(length & ~3) & 0xff
        h *= m
      case 0 =>
      // fall through
    }

    h ^= h >>> 13
    h *= m
    h ^= h >>> 15
    h
  }

  // To match https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/clients/producer/internals/DefaultPartitioner.java#L59
  def shardId(entityId: String, nrShards: Int): String =
    (toPositive(murmur2(entityId.getBytes(StandardCharsets.UTF_8))) % nrShards).toString
}
