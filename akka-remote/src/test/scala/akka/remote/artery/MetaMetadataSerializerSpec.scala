/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import org.scalacheck.{ Arbitrary, Gen }
import org.scalatest.prop.Checkers
import org.scalatest.{ Matchers, WordSpec }

class MetaMetadataSerializerSpec extends WordSpec with Matchers with Checkers {

  case class KeyLen(k: Key, l: Len) {
    override def toString = s" key = ${k}, len = ${l}"
  }
  type Key = Byte
  type Len = Int

  implicit val arbitraryKeyLength: Arbitrary[KeyLen] = Arbitrary {
    for {
      key ← Gen.chooseNum(0.toByte, 31.toByte)
      len ← Gen.chooseNum(1, 1024)
    } yield KeyLen(key, len)
  }

  "MetaMetadataSerializer" must {

    "perform roundtrip masking/unmasking of entry key+length" in {
      val key: Byte = 17
      val len = 812
      val kl = MetadataEnvelopeSerializer.muxEntryKeyLength(key, len)

      val key2 = MetadataEnvelopeSerializer.unmaskEntryKey(kl)
      key2 should ===(key)
      val len2 = MetadataEnvelopeSerializer.unmaskEntryLength(kl)
      len2 should ===(len)
    }

    "perform key roundtrip using mask/unmask" in {
      check { (kl: KeyLen) ⇒
        val k = kl.k

        val masked = MetadataEnvelopeSerializer.maskEntryKey(k)
        val uk = MetadataEnvelopeSerializer.unmaskEntryKey(masked)
        uk should ===(k)
        uk == k
      }
    }
    "perform length roundtrip using mask/unmask" in {
      check { (kl: KeyLen) ⇒
        val l = kl.l

        val masked = MetadataEnvelopeSerializer.maskEntryLength(l)
        val ul = MetadataEnvelopeSerializer.unmaskEntryLength(masked)
        ul should ===(l)
        ul == l
      }
    }
    "perform muxed roundtrip using mask/unmask" in {
      check { (kl: KeyLen) ⇒
        val k = kl.k
        val l = kl.l

        val masked = MetadataEnvelopeSerializer.muxEntryKeyLength(k, l)
        val uk = MetadataEnvelopeSerializer.unmaskEntryKey(masked)
        uk should ===(k)
        val ul = MetadataEnvelopeSerializer.unmaskEntryLength(masked)
        ul should ===(l)
        ul == l && uk == k
      }
    }

  }
}
