/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import org.scalatest.WordSpec
import akka.http.model._
import akka.http.util._
import HttpMethods.POST

import java.io.{ InputStream, OutputStream, ByteArrayInputStream, ByteArrayOutputStream }
import java.util.zip.{ ZipException, DataFormatException, GZIPInputStream, GZIPOutputStream }

import akka.util.ByteString

import scala.annotation.tailrec

class GzipSpec extends WordSpec with CodecSpecSupport {

  "The Gzip codec" should {
    "properly encode a small string" in {
      streamGunzip(ourGzip(smallTextBytes)) should readAs(smallText)
    }
    "properly decode a small string" in {
      ourGunzip(streamGzip(smallTextBytes)) should readAs(smallText)
    }
    "properly round-trip encode/decode a small string" in {
      ourGunzip(ourGzip(smallTextBytes)) should readAs(smallText)
    }
    "properly encode a large string" in {
      streamGunzip(ourGzip(largeTextBytes)) should readAs(largeText)
    }
    "properly decode a large string" in {
      ourGunzip(streamGzip(largeTextBytes)) should readAs(largeText)
    }
    "properly round-trip encode/decode a large string" in {
      ourGunzip(ourGzip(largeTextBytes)) should readAs(largeText)
    }
    "properly round-trip encode/decode an HttpRequest" in {
      val request = HttpRequest(POST, entity = HttpEntity(largeText))
      Gzip.decode(Gzip.encode(request)) should equal(request)
    }
    "provide a better compression ratio than the standard Gzip/Gunzip streams" in {
      ourGzip(largeTextBytes).length should be < streamGzip(largeTextBytes).length
    }
    "properly decode concatenated compressions" in {
      ourGunzip(Seq(gzip("Hello,"), gzip(" dear "), gzip("User!")).join) should readAs("Hello, dear User!")
    }
    "throw an error on corrupt input" in {
      val ex = the[DataFormatException] thrownBy ourGunzip(corruptGzipContent)
      ex.getMessage should equal("invalid literal/length code")
    }
    "throw an error on truncated input" in {
      val ex = the[ZipException] thrownBy ourGunzip(streamGzip(smallTextBytes).dropRight(5))
      ex.getMessage should equal("Truncated GZIP stream")
    }
    "throw early if header is corrupt" in {
      val ex = the[ZipException] thrownBy ourGunzip(ByteString(0, 1, 2, 3, 4))
      ex.getMessage should equal("Not in GZIP format")
    }
    "not throw an error if a subsequent block is corrupt" in {
      pending // FIXME: should we read as long as possible and only then report an error, that seems somewhat arbitrary
      ourGunzip(Seq(gzip("Hello,"), gzip(" dear "), corruptGzipContent).join) should readAs("Hello, dear ")
    }
    "decompress in very small chunks" in {
      val compressed = gzip("Hello")
      val decomp = Gzip.newDecompressor
      val result = decomp.decompress(compressed.take(10)) // just the headers
      result.size should equal(0)
      val data = decomp.decompress(compressed.drop(10)) // the rest
      data should readAs("Hello")
    }
    "support chunked round-trip encoding/decoding" in {
      val chunks = largeTextBytes.grouped(512).toVector
      val comp = Gzip.newCompressor
      val decomp = Gzip.newDecompressor
      val chunks2 =
        chunks.map { chunk ⇒ decomp.decompress(comp.compressAndFlush(chunk)) } :+ decomp.decompress(comp.finish())
      chunks2.join should readAs(largeText)
    }
    "works for any split in prefix + suffix" in {
      val compressed = streamGzip(smallTextBytes)
      def tryWithPrefixOfSize(prefixSize: Int): Unit = {
        val decomp = Gzip.newDecompressor
        val prefix = compressed.take(prefixSize)
        val suffix = compressed.drop(prefixSize)

        decomp.decompress(prefix) ++ decomp.decompress(suffix) should readAs(smallText)
      }
      (0 to compressed.size).foreach(tryWithPrefixOfSize)
    }
    "works for chunked compressed data of sizes just above 1024" in {
      val comp = new GzipCompressor
      val decomp = new GzipDecompressor

      val inputBytes = ByteString("""{"baseServiceURL":"http://www.acme.com","endpoints":{"assetSearchURL":"/search","showsURL":"/shows","mediaContainerDetailURL":"/container","featuredTapeURL":"/tape","assetDetailURL":"/asset","moviesURL":"/movies","recentlyAddedURL":"/recent","topicsURL":"/topics","scheduleURL":"/schedule"},"urls":{"aboutAweURL":"www.foobar.com"},"channelName":"Cool Stuff","networkId":"netId","slotProfile":"slot_1","brag":{"launchesUntilPrompt":10,"daysUntilPrompt":5,"launchesUntilReminder":5,"daysUntilReminder":2},"feedbackEmailAddress":"feedback@acme.com","feedbackEmailSubject":"Commends from User","splashSponsor":[],"adProvider":{"adProviderProfile":"","adProviderProfileAndroid":"","adProviderNetworkID":0,"adProviderSiteSectionNetworkID":0,"adProviderVideoAssetNetworkID":0,"adProviderSiteSectionCustomID":{},"adProviderServerURL":"","adProviderLiveVideoAssetID":""},"update":[{"forPlatform":"ios","store":{"iTunes":"www.something.com"},"minVer":"1.2.3","notificationVer":"1.2.5"},{"forPlatform":"android","store":{"amazon":"www.something.com","play":"www.something.com"},"minVer":"1.2.3","notificationVer":"1.2.5"}],"tvRatingPolicies":[{"type":"sometype","imageKey":"tv_rating_small","durationMS":15000,"precedence":1},{"type":"someothertype","imageKey":"tv_rating_big","durationMS":15000,"precedence":2}],"exts":{"adConfig":{"globals":{"#{adNetworkID}":"2620","#{ssid}":"usa_tveapp"},"iPad":{"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/shows","adSize":[{"#{height}":90,"#{width}":728}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad&sz=1x1&t=&c=#{doubleclickrandom}"},"watchwithshowtile":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/watchwithshowtile","adSize":[{"#{height}":120,"#{width}":240}]},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPadRetina":{"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/shows","adSize":[{"#{height}":90,"#{width}":728}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad&sz=1x1&t=&c=#{doubleclickrandom}"},"watchwithshowtile":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/watchwithshowtile","adSize":[{"#{height}":120,"#{width}":240}]},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPhone":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPhoneRetina":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"Tablet":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/home","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"TabletHD":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/home","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"Phone":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_android/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"PhoneHD":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_android/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}}}}}""", "utf8")
      val compressed = comp.compressAndFinish(inputBytes)

      val decompressed = decomp.decompress(compressed)
      decompressed should equal(inputBytes)
    }
  }

  def gzip(s: String) = ourGzip(ByteString(s, "UTF8"))
  def ourGzip(bytes: ByteString): ByteString = Gzip.encode(bytes)
  def ourGunzip(bytes: ByteString): ByteString = Gzip.decode(bytes)

  lazy val corruptGzipContent = {
    val content = gzip("Hello").toArray
    content(14) = 26.toByte
    ByteString(content)
  }

  def streamGzip(bytes: ByteString): ByteString = {
    val output = new ByteArrayOutputStream()
    val gos = new GZIPOutputStream(output); gos.write(bytes.toArray); gos.close()
    ByteString(output.toByteArray)
  }

  def streamGunzip(bytes: ByteString): ByteString = {
    val output = new ByteArrayOutputStream()
    val input = new GZIPInputStream(new ByteArrayInputStream(bytes.toArray))

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

}
