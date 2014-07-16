/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import java.io.File
import akka.http.routing.RoutingSettings
import org.scalatest.{ Inside, Inspectors }
import scala.util.Properties
import akka.http.model._
import akka.http.util._
import akka.http.unmarshalling._
import MediaTypes._
import headers._
import HttpCharsets._

class FileAndResourceDirectivesSpec extends RoutingSpec with Inspectors with Inside {

  override def testConfigSource =
    """spray.routing {
      |  file-chunking-threshold-size = 16
      |  file-chunking-chunk-size = 8
      |  range-coalescing-threshold = 1
      |}""".stripMargin

  "getFromFile" should {
    "reject non-GET requests" in {
      Put() ~> getFromFile("some") ~> check { handled mustEqual (false) }
    }
    "reject requests to non-existing files" in {
      Get() ~> getFromFile("nonExistentFile") ~> check { handled mustEqual (false) }
    }
    "reject requests to directories" in {
      Get() ~> getFromFile(Properties.javaHome) ~> check { handled mustEqual (false) }
    }
    "return the file content with the MediaType matching the file extension" in {
      val file = File.createTempFile("sprayTest", ".PDF")
      try {
        writeAllText("This is PDF", file)
        Get() ~> getFromFile(file.getPath) ~> check {
          mediaType mustEqual `application/pdf`
          definedCharset mustEqual None
          body.asString mustEqual "This is PDF"
          headers must contain(`Last-Modified`(DateTime(file.lastModified)))
        }
      } finally file.delete
    }
    "return the file content with MediaType 'application/octet-stream' on unknown file extensions" in {
      val file = File.createTempFile("sprayTest", null)
      try {
        writeAllText("Some content", file)
        Get() ~> getFromFile(file) ~> check {
          mediaType mustEqual `application/octet-stream`
          body.asString mustEqual "Some content"
        }
      } finally file.delete
    }

    "return a single range from a file" in {
      val file = File.createTempFile("partialTest", null)
      try {
        writeAllText("ABCDEFGHIJKLMNOPQRSTUVWXYZ", file)
        Get() ~> addHeader(Range(ByteRange(0, 10))) ~> getFromFile(file) ~> check {
          body.asString mustEqual "ABCDEFGHIJK"
          status mustEqual StatusCodes.PartialContent
          headers must contain(`Content-Range`(ContentRange(0, 10, 26)))
        }
      } finally file.delete
    }

    "return multiple ranges from a file at once" in pending /*{
      val file = File.createTempFile("partialTest", null)
      implicit val settingsWithDisabledAutoChunking = new RoutingSettings(true, 0, Int.MaxValue, false, null, true, 10, 1)
      try {
        writeAllText("ABCDEFGHIJKLMNOPQRSTUVWXYZ", file)
        val rangeHeader = Range(ByteRange(1, 10), ByteRange.suffix(10))
        Get() ~> addHeader(rangeHeader) ~> getFromFile(file, ContentTypes.`text/plain`) ~> check {
          val parts = responseAs[MultipartByteRanges].parts
          parts.size mustEqual 2
          parts(0).entity.data.asString mustEqual "BCDEFGHIJK"
          parts(1).entity.data.asString mustEqual "QRSTUVWXYZ"

          status mustEqual StatusCodes.PartialContent
          headers must not(contain(like[HttpHeader] { case `Content-Range`(_, _) ⇒ ok }))
          mediaType.withParameters(Map.empty) mustEqual `multipart/byteranges`
        }
      } finally file.delete
    }*/

    "return a chunked response for files larger than the configured file-chunking-threshold-size" in pending /*{
      val file = File.createTempFile("sprayTest2", ".xml")
      try {
        writeAllText("<this could be XML if it were formatted correctly>", file)
        Get() ~> getFromFile(file) ~> check {
          mediaType mustEqual `text/xml`
          definedCharset mustEqual Some(`UTF-8`)
          body.asString mustEqual "<this co"
          headers must contain(`Last-Modified`(DateTime(file.lastModified)))
          chunks.map(_.data.asString).mkString("|") mustEqual "uld be X|ML if it| were fo|rmatted |correctl|y>"
        }
      } finally file.delete
    }*/
  }

  "getFromResource" should {
    "reject non-GET requests" in {
      Put() ~> getFromResource("some") ~> check { handled mustEqual (false) }
    }
    "reject requests to non-existing resources" in {
      Get() ~> getFromResource("nonExistingResource") ~> check { handled mustEqual (false) }
    }
    "return the resource content with the MediaType matching the file extension" in pending /*{
      val route = getFromResource("sample.html")

      def runCheck =
        Get() ~> route ~> check {
          mediaType mustEqual `text/html`
          body.asString mustEqual "<p>Lorem"
          forAtLeast(1, headers) { h ⇒
            inside(h) {
              case `Last-Modified`(dt) ⇒
                DateTime(2011, 7, 1) must be < dt
                dt.clicks must be < System.currentTimeMillis()
            }
          }
          chunks.map(_.data.asString).mkString mustEqual " ipsum!</p>"
        }

      runCheck
      runCheck // additional test to check that no internal state is kept
    }*/
    "return the file content with MediaType 'application/octet-stream' on unknown file extensions" in {
      Get() ~> getFromResource("sample.xyz") ~> check {
        mediaType mustEqual `application/octet-stream`
        body.asString mustEqual "XyZ"
      }
    }
  }

  "getFromResourceDirectory" should {
    "reject requests to non-existing resources" in {
      Get("not/found") ~> getFromResourceDirectory("subDirectory") ~> check { handled mustEqual (false) }
    }
    "return the resource content with the MediaType matching the file extension" in {
      val verify = check {
        mediaType mustEqual `application/pdf`
        body.asString mustEqual "123"
      }
      "example 1" in { Get("empty.pdf") ~> getFromResourceDirectory("subDirectory") ~> verify }
      "example 2" in { Get("empty.pdf") ~> getFromResourceDirectory("subDirectory/") ~> verify }
      "example 3" in { Get("subDirectory/empty.pdf") ~> getFromResourceDirectory("") ~> verify }
    }
    "reject requests to directory resources" in {
      Get() ~> getFromResourceDirectory("subDirectory") ~> check { handled mustEqual (false) }
    }
  }

  "listDirectoryContents" should {
    val base = new File(getClass.getClassLoader.getResource("").toURI).getPath
    new File(base, "subDirectory/emptySub").mkdir()
    def eraseDateTime(s: String) = s.replaceAll("""\d\d\d\d-\d\d-\d\d \d\d:\d\d:\d\d""", "xxxx-xx-xx xx:xx:xx")
    implicit val settings = RoutingSettings.default.copy(renderVanityFooter = false)

    "properly render a simple directory" in {
      Get() ~> listDirectoryContents(base + "/someDir") ~> check {
        eraseDateTime(responseAs[String]) mustEqual prep {
          """<html>
            |<head><title>Index of /</title></head>
            |<body>
            |<h1>Index of /</h1>
            |<hr>
            |<pre>
            |<a href="/sub/">sub/</a>             xxxx-xx-xx xx:xx:xx
            |<a href="/fileA.txt">fileA.txt</a>        xxxx-xx-xx xx:xx:xx            3  B
            |<a href="/fileB.xml">fileB.xml</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render a sub directory" in {
      Get("/sub/") ~> listDirectoryContents(base + "/someDir") ~> check {
        eraseDateTime(responseAs[String]) mustEqual prep {
          """<html>
            |<head><title>Index of /sub/</title></head>
            |<body>
            |<h1>Index of /sub/</h1>
            |<hr>
            |<pre>
            |<a href="/">../</a>
            |<a href="/sub/file.html">file.html</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render the union of several directories" in {
      Get() ~> listDirectoryContents(base + "/someDir", base + "/subDirectory") ~> check {
        eraseDateTime(responseAs[String]) mustEqual prep {
          """<html>
            |<head><title>Index of /</title></head>
            |<body>
            |<h1>Index of /</h1>
            |<hr>
            |<pre>
            |<a href="/emptySub/">emptySub/</a>        xxxx-xx-xx xx:xx:xx
            |<a href="/sub/">sub/</a>             xxxx-xx-xx xx:xx:xx
            |<a href="/empty.pdf">empty.pdf</a>        xxxx-xx-xx xx:xx:xx            3  B
            |<a href="/fileA.txt">fileA.txt</a>        xxxx-xx-xx xx:xx:xx            3  B
            |<a href="/fileB.xml">fileB.xml</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render an empty sub directory with vanity footer" in {
      val settings = 0 // shadow implicit
      Get("/emptySub/") ~> listDirectoryContents(base + "/subDirectory") ~> check {
        eraseDateTime(responseAs[String]) mustEqual prep {
          """<html>
            |<head><title>Index of /emptySub/</title></head>
            |<body>
            |<h1>Index of /emptySub/</h1>
            |<hr>
            |<pre>
            |<a href="/">../</a>
            |</pre>
            |<hr>
            |<div style="width:100%;text-align:right;color:gray">
            |<small>rendered by <a href="http://spray.io">spray</a> on xxxx-xx-xx xx:xx:xx</small>
            |</div>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render an empty top-level directory" in {
      Get() ~> listDirectoryContents(base + "/subDirectory/emptySub") ~> check {
        eraseDateTime(responseAs[String]) mustEqual prep {
          """<html>
            |<head><title>Index of /</title></head>
            |<body>
            |<h1>Index of /</h1>
            |<hr>
            |<pre>
            |(no files)
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render a simple directory with a path prefix" in {
      Get("/files/") ~> pathPrefix("files")(listDirectoryContents(base + "/someDir")) ~> check {
        eraseDateTime(responseAs[String]) mustEqual prep {
          """<html>
            |<head><title>Index of /files/</title></head>
            |<body>
            |<h1>Index of /files/</h1>
            |<hr>
            |<pre>
            |<a href="/files/sub/">sub/</a>             xxxx-xx-xx xx:xx:xx
            |<a href="/files/fileA.txt">fileA.txt</a>        xxxx-xx-xx xx:xx:xx            3  B
            |<a href="/files/fileB.xml">fileB.xml</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render a sub directory with a path prefix" in {
      Get("/files/sub/") ~> pathPrefix("files")(listDirectoryContents(base + "/someDir")) ~> check {
        eraseDateTime(responseAs[String]) mustEqual prep {
          """<html>
            |<head><title>Index of /files/sub/</title></head>
            |<body>
            |<h1>Index of /files/sub/</h1>
            |<hr>
            |<pre>
            |<a href="/files/">../</a>
            |<a href="/files/sub/file.html">file.html</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render an empty top-level directory with a path prefix" in {
      Get("/files/") ~> pathPrefix("files")(listDirectoryContents(base + "/subDirectory/emptySub")) ~> check {
        eraseDateTime(responseAs[String]) mustEqual prep {
          """<html>
            |<head><title>Index of /files/</title></head>
            |<body>
            |<h1>Index of /files/</h1>
            |<hr>
            |<pre>
            |(no files)
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "reject requests to file resources" in {
      Get() ~> listDirectoryContents(base + "subDirectory/empty.pdf") ~> check { handled mustEqual (false) }
    }
  }

  def prep(s: String) = s.stripMarginWithNewline("\n")

  def writeAllText(text: String, file: File): Unit = akka.http.routing.FIXME
}
