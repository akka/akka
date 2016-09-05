/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.util.OptionVal
import org.scalatest.{ Matchers, WordSpec }

import scala.util.Random

class MetaMetadataSerializerSpec extends WordSpec with Matchers {

  "MetaMetadataSerializer" must {

    "perform roundtrip masking/unmasking of entry key+length" in {
      val key: Byte = 13
      val len = 1337
      val kl = MetaMetadataSerializer.muxEntryKeyLength(key, len)
      MetaMetadataSerializer.unmaskEntryKey(kl) should ===(key)
      MetaMetadataSerializer.unmaskEntryLength(kl) should ===(len)
    }

  }
}
