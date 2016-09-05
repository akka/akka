/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import org.scalatest.{ Matchers, WordSpec }

class MetaMetadataSerializerSpec extends WordSpec with Matchers {
  // TODO scalacheck

  "MetaMetadataSerializer" must {

    "perform roundtrip masking/unmasking of entry key+length" in {
      val key: Byte = 1
      val len = 7
      val kl = MetadataEnvelopeSerializer.muxEntryKeyLength(key, len)

      val key2 = MetadataEnvelopeSerializer.unmaskEntryKey(kl)
      key2 should ===(key)
      val len2 = MetadataEnvelopeSerializer.unmaskEntryLength(kl)
      len2 should ===(len)
    }

  }
}
