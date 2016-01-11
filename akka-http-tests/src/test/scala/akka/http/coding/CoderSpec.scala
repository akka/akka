/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import org.scalatest.{ Inspectors, WordSpec }
import scala.annotation.tailrec

import scala.concurrent.duration._

import java.io.{ OutputStream, InputStream, ByteArrayInputStream, ByteArrayOutputStream }
import java.util
import java.util.zip.DataFormatException

import akka.http.util._

import akka.http.model.HttpMethods._
import akka.http.model.{ HttpEntity, HttpRequest }
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.util.control.NoStackTrace

abstract class CoderSpec extends WordSpec with CodecSpecSupport with Inspectors {
  protected def Coder: Coder with StreamDecoder
  protected def newDecodedInputStream(underlying: InputStream): InputStream
  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream

  case object AllDataAllowed extends Exception with NoStackTrace
  protected def corruptInputMessage: Option[String]

  def extraTests(): Unit = {}

  s"The ${Coder.encoding.value} codec" should {
    "properly encode a small string" in {
      streamDecode(ourEncode(smallTextBytes)) should readAs(smallText)
    }
    "properly decode a small string" in {
      ourDecode(streamEncode(smallTextBytes)) should readAs(smallText)
    }
    "properly round-trip encode/decode a small string" in {
      ourDecode(ourEncode(smallTextBytes)) should readAs(smallText)
    }
    "properly encode a large string" in {
      streamDecode(ourEncode(largeTextBytes)) should readAs(largeText)
    }
    "properly decode a large string" in {
      ourDecode(streamEncode(largeTextBytes)) should readAs(largeText)
    }
    "properly round-trip encode/decode a large string" in {
      ourDecode(ourEncode(largeTextBytes)) should readAs(largeText)
    }
    "properly round-trip encode/decode an HttpRequest" in {
      val request = HttpRequest(POST, entity = HttpEntity(largeText))
      Coder.decode(Coder.encode(request)) should equal(request)
    }
    "throw an error on corrupt input" in {
      corruptInputMessage foreach { message ⇒
        val ex = the[DataFormatException] thrownBy ourDecode(corruptContent)
        ex.getMessage should equal(message)
      }
    }
    "not throw an error if a subsequent block is corrupt" in {
      pending // FIXME: should we read as long as possible and only then report an error, that seems somewhat arbitrary
      ourDecode(Seq(encode("Hello,"), encode(" dear "), corruptContent).join) should readAs("Hello, dear ")
    }
    "decompress in very small chunks" in {
      val compressed = encode("Hello")

      decodeChunks(Source(Vector(compressed.take(10), compressed.drop(10)))) should readAs("Hello")
    }
    "support chunked round-trip encoding/decoding" in {
      val chunks = largeTextBytes.grouped(512).toVector
      val comp = Coder.newCompressor
      val compressedChunks = chunks.map { chunk ⇒ comp.compressAndFlush(chunk) } :+ comp.finish()
      val uncompressed = Coder.decodeFromIterator(compressedChunks.iterator)

      uncompressed should readAs(largeText)
    }
    "works for any split in prefix + suffix" in {
      val compressed = streamEncode(smallTextBytes)
      def tryWithPrefixOfSize(prefixSize: Int): Unit = {
        val prefix = compressed.take(prefixSize)
        val suffix = compressed.drop(prefixSize)

        decodeChunks(Source(prefix :: suffix :: Nil)) should readAs(smallText)
      }
      (0 to compressed.size).foreach(tryWithPrefixOfSize)
    }
    "works for chunked compressed data of sizes just above 1024" in {
      val comp = Coder.newCompressor
      val inputBytes = ByteString("""{"baseServiceURL":"http://www.acme.com","endpoints":{"assetSearchURL":"/search","showsURL":"/shows","mediaContainerDetailURL":"/container","featuredTapeURL":"/tape","assetDetailURL":"/asset","moviesURL":"/movies","recentlyAddedURL":"/recent","topicsURL":"/topics","scheduleURL":"/schedule"},"urls":{"aboutAweURL":"www.foobar.com"},"channelName":"Cool Stuff","networkId":"netId","slotProfile":"slot_1","brag":{"launchesUntilPrompt":10,"daysUntilPrompt":5,"launchesUntilReminder":5,"daysUntilReminder":2},"feedbackEmailAddress":"feedback@acme.com","feedbackEmailSubject":"Commends from User","splashSponsor":[],"adProvider":{"adProviderProfile":"","adProviderProfileAndroid":"","adProviderNetworkID":0,"adProviderSiteSectionNetworkID":0,"adProviderVideoAssetNetworkID":0,"adProviderSiteSectionCustomID":{},"adProviderServerURL":"","adProviderLiveVideoAssetID":""},"update":[{"forPlatform":"ios","store":{"iTunes":"www.something.com"},"minVer":"1.2.3","notificationVer":"1.2.5"},{"forPlatform":"android","store":{"amazon":"www.something.com","play":"www.something.com"},"minVer":"1.2.3","notificationVer":"1.2.5"}],"tvRatingPolicies":[{"type":"sometype","imageKey":"tv_rating_small","durationMS":15000,"precedence":1},{"type":"someothertype","imageKey":"tv_rating_big","durationMS":15000,"precedence":2}],"exts":{"adConfig":{"globals":{"#{adNetworkID}":"2620","#{ssid}":"usa_tveapp"},"iPad":{"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/shows","adSize":[{"#{height}":90,"#{width}":728}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad&sz=1x1&t=&c=#{doubleclickrandom}"},"watchwithshowtile":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/watchwithshowtile","adSize":[{"#{height}":120,"#{width}":240}]},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPadRetina":{"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/shows","adSize":[{"#{height}":90,"#{width}":728}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad&sz=1x1&t=&c=#{doubleclickrandom}"},"watchwithshowtile":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/watchwithshowtile","adSize":[{"#{height}":120,"#{width}":240}]},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPhone":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPhoneRetina":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"Tablet":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/home","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"TabletHD":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/home","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"Phone":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_android/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"PhoneHD":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_android/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}}}}}""", "utf8")
      val compressed = comp.compressAndFinish(inputBytes)

      ourDecode(compressed) should equal(inputBytes)
    }

    extraTests()

    "shouldn't produce huge ByteStrings for some input" in {
      val array = new Array[Byte](10) // FIXME
      util.Arrays.fill(array, 1.toByte)
      val compressed = streamEncode(ByteString(array))
      val limit = 10000
      val resultBs =
        Source.singleton(compressed)
          .via(Coder.withMaxBytesPerChunk(limit).decoderFlow)
          .collectAll
          .awaitResult(1.second)

      forAll(resultBs) { bs ⇒
        bs.length should be < limit
        bs.forall(_ == 1) should equal(true)
      }
    }
  }

  def encode(s: String) = ourEncode(ByteString(s, "UTF8"))
  def ourEncode(bytes: ByteString): ByteString = Coder.encode(bytes)
  def ourDecode(bytes: ByteString): ByteString = Coder.decode(bytes)

  lazy val corruptContent = {
    val content = encode(largeText).toArray
    content(14) = 26.toByte
    ByteString(content)
  }

  def streamEncode(bytes: ByteString): ByteString = {
    val output = new ByteArrayOutputStream()
    val gos = newEncodedOutputStream(output); gos.write(bytes.toArray); gos.close()
    ByteString(output.toByteArray)
  }

  def streamDecode(bytes: ByteString): ByteString = {
    val output = new ByteArrayOutputStream()
    val input = newDecodedInputStream(new ByteArrayInputStream(bytes.toArray))

    val buffer = new Array[Byte](500)
    @tailrec def copy(from: InputStream, to: OutputStream): Unit = {
      val read = from.read(buffer)
      if (read >= 0) {
        to.write(buffer, 0, read)
        copy(from, to)
      }
    }

    copy(input, output)
    ByteString(output.toByteArray)
  }

  def decodeChunks(input: Source[ByteString]): ByteString =
    input.via(Coder.decoderFlow).join.awaitResult(3.seconds)
}
