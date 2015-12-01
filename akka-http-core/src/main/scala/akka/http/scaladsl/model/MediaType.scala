/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

import language.implicitConversions
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._

/**
 * A MediaType describes the type of the content of an HTTP message entity.
 *
 * While knowledge of the MediaType alone suffices for being able to properly interpret binary content this
 * is not generally the case for non-binary (i.e. character-based) content, which also requires the definition
 * of a specific character encoding ([[HttpCharset]]).
 * Therefore [[MediaType]] instances are frequently encountered as a member of a [[ContentType]], which
 * groups a [[MediaType]] with a potentially required [[HttpCharset]] to hold everything required for being
 * able to interpret an [[HttpEntity]].
 *
 * MediaTypes come in three basic forms:
 *
 * 1. Binary: These do not need an additional [[HttpCharset]] to be able to form a [[ContentType]]. Therefore
 *    they can be implicitly converted to the latter.
 *
 * 2. WithOpenCharset: Most character-based MediaTypes are of this form, which can be combined with all
 *    [[HttpCharset]] instances to form a [[ContentType]].
 *
 * 3. WithFixedCharset: Some character-based MediaTypes prescribe a single, clearly defined charset and as such,
 *    similarly to binary MediaTypes, do not require the addition of an [[HttpCharset]] instances to form a
 *    [[ContentType]]. The most prominent example is probably `application/json` which must always be UTF-8 encoded.
 *    Like binary MediaTypes `WithFixedCharset` types can be implicitly converted to a [[ContentType]].
 */
sealed abstract class MediaType extends jm.MediaType with LazyValueBytesRenderable with WithQValue[MediaRange] {

  def fileExtensions: List[String]
  def params: Map[String, String]

  override def isApplication: Boolean = false
  override def isAudio: Boolean = false
  override def isImage: Boolean = false
  override def isMessage: Boolean = false
  override def isMultipart: Boolean = false
  override def isText: Boolean = false
  override def isVideo: Boolean = false

  def withParams(params: Map[String, String]): MediaType

  def withQValue(qValue: Float): MediaRange = MediaRange(this, qValue.toFloat)

  override def equals(that: Any): Boolean =
    that match {
      case x: MediaType ⇒ value equalsIgnoreCase x.value
      case _            ⇒ false
    }

  override def hashCode(): Int = value.hashCode

  /**
   * JAVA API
   */
  def toRange = jm.MediaRanges.create(this)
  def toRange(qValue: Float) = jm.MediaRanges.create(this, qValue)
}

object MediaType {

  def applicationBinary(subType: String, compressible: Boolean, fileExtensions: String*): Binary =
    new Binary("application/" + subType, "application", subType, compressible, fileExtensions.toList) {
      override def isApplication = true
    }

  def applicationWithFixedCharset(subType: String, charset: HttpCharset,
                                  fileExtensions: String*): WithFixedCharset =
    new WithFixedCharset("application/" + subType, "application", subType, charset, fileExtensions.toList) {
      override def isApplication = true
    }

  def applicationWithOpenCharset(subType: String, fileExtensions: String*): WithOpenCharset =
    new NonMultipartWithOpenCharset("application/" + subType, "application", subType, fileExtensions.toList) {
      override def isApplication = true
    }

  def audio(subType: String, compressible: Boolean, fileExtensions: String*): Binary =
    new Binary("audio/" + subType, "audio", subType, compressible, fileExtensions.toList) {
      override def isAudio = true
    }

  def image(subType: String, compressible: Boolean, fileExtensions: String*): Binary =
    new Binary("image/" + subType, "image", subType, compressible, fileExtensions.toList) {
      override def isImage = true
    }

  def message(subType: String, compressible: Boolean, fileExtensions: String*): Binary =
    new Binary("message/" + subType, "message", subType, compressible, fileExtensions.toList) {
      override def isMessage = true
    }

  def text(subType: String, fileExtensions: String*): WithOpenCharset =
    new NonMultipartWithOpenCharset("text/" + subType, "text", subType, fileExtensions.toList) {
      override def isText = true
    }

  def video(subType: String, compressible: Boolean, fileExtensions: String*): Binary =
    new Binary("video/" + subType, "video", subType, compressible, fileExtensions.toList) {
      override def isVideo = true
    }

  def customBinary(mainType: String, subType: String, compressible: Boolean, fileExtensions: List[String] = Nil,
                   params: Map[String, String] = Map.empty, allowArbitrarySubtypes: Boolean = false): Binary = {
    require(mainType != "multipart", "Cannot create a MediaType.Multipart here, use `customMultipart` instead!")
    require(allowArbitrarySubtypes || subType != "*", "Cannot create a MediaRange here, use `MediaRange.custom` instead!")
    val _params = params
    new Binary(renderValue(mainType, subType, params), mainType, subType, compressible, fileExtensions) {
      override def params = _params
      override def isApplication = mainType == "application"
      override def isAudio = mainType == "audio"
      override def isImage = mainType == "image"
      override def isMessage = mainType == "message"
      override def isText = mainType == "text"
      override def isVideo = mainType == "video"
    }
  }

  def customWithFixedCharset(mainType: String, subType: String, charset: HttpCharset, fileExtensions: List[String] = Nil,
                             params: Map[String, String] = Map.empty,
                             allowArbitrarySubtypes: Boolean = false): WithFixedCharset = {
    require(mainType != "multipart", "Cannot create a MediaType.Multipart here, use `customMultipart` instead!")
    require(allowArbitrarySubtypes || subType != "*", "Cannot create a MediaRange here, use `MediaRange.custom` instead!")
    val _params = params
    new WithFixedCharset(renderValue(mainType, subType, params), mainType, subType, charset, fileExtensions) {
      override def params = _params
      override def isApplication = mainType == "application"
      override def isAudio = mainType == "audio"
      override def isImage = mainType == "image"
      override def isMessage = mainType == "message"
      override def isText = mainType == "text"
      override def isVideo = mainType == "video"
    }
  }

  def customWithOpenCharset(mainType: String, subType: String, fileExtensions: List[String] = Nil,
                            params: Map[String, String] = Map.empty,
                            allowArbitrarySubtypes: Boolean = false): WithOpenCharset = {
    require(mainType != "multipart", "Cannot create a MediaType.Multipart here, use `customMultipart` instead!")
    require(allowArbitrarySubtypes || subType != "*", "Cannot create a MediaRange here, use `MediaRange.custom` instead!")
    val _params = params
    new NonMultipartWithOpenCharset(renderValue(mainType, subType, params), mainType, subType, fileExtensions) {
      override def params = _params
      override def isApplication = mainType == "application"
      override def isAudio = mainType == "audio"
      override def isImage = mainType == "image"
      override def isMessage = mainType == "message"
      override def isText = mainType == "text"
      override def isVideo = mainType == "video"
    }
  }

  def customMultipart(subType: String, params: Map[String, String]): Multipart = {
    require(subType != "*", "Cannot create a MediaRange here, use MediaRanges.`multipart/*` instead!")
    new Multipart(subType, params)
  }

  def custom(value: String, binary: Boolean, compressible: Boolean = true,
             fileExtensions: List[String] = Nil): MediaType = {
    val parts = value.split('/')
    require(parts.length == 2, s"`$value` is not a valid media-type. It must consist of two parts separated by '/'.")
    if (binary) customBinary(parts(0), parts(1), compressible, fileExtensions)
    else customWithOpenCharset(parts(0), parts(1), fileExtensions)
  }

  /**
   * Tries to parse a ``MediaType`` value from the given String.
   * Returns ``Right(mediaType)`` if successful and ``Left(errors)`` otherwise.
   */
  def parse(value: String): Either[List[ErrorInfo], MediaType] =
    ContentType.parse(value).right.map(_.mediaType)

  def unapply(mediaType: MediaType): Option[String] = Some(mediaType.value)

  /////////////////////////////////////////////////////////////////////////

  private def renderValue(mainType: String, subType: String, params: Map[String, String]): String = {
    val r = new StringRendering ~~ mainType ~~ '/' ~~ subType
    if (params.nonEmpty) params foreach { case (k, v) ⇒ r ~~ ';' ~~ ' ' ~~ k ~~ '=' ~~# v }
    r.get
  }

  sealed abstract class Binary(val value: String, val mainType: String, val subType: String, val compressible: Boolean,
                               val fileExtensions: List[String]) extends MediaType with jm.MediaType.Binary {
    def binary = true
    def params: Map[String, String] = Map.empty
    def withParams(params: Map[String, String]): Binary with MediaType =
      customBinary(mainType, subType, compressible, fileExtensions, params)

    /**
     * JAVA API
     */
    def toContentType: ContentType.Binary = ContentType(this)
  }

  sealed abstract class NonBinary extends MediaType with jm.MediaType.NonBinary {
    def binary = false
    def compressible = true
  }

  sealed abstract class WithFixedCharset(val value: String, val mainType: String, val subType: String,
                                         val charset: HttpCharset, val fileExtensions: List[String])
    extends NonBinary with jm.MediaType.WithFixedCharset {
    def params: Map[String, String] = Map.empty
    def withParams(params: Map[String, String]): WithFixedCharset with MediaType =
      customWithFixedCharset(mainType, subType, charset, fileExtensions, params)

    /**
     * JAVA API
     */
    def toContentType: ContentType.WithFixedCharset = ContentType(this)
  }

  sealed abstract class WithOpenCharset extends NonBinary with jm.MediaType.WithOpenCharset {
    def withCharset(charset: HttpCharset): ContentType.WithCharset = ContentType(this, charset)

    /**
     * JAVA API
     */
    def toContentType(charset: jm.HttpCharset): ContentType.WithCharset = withCharset(charset.asScala)
  }

  sealed abstract class NonMultipartWithOpenCharset(val value: String, val mainType: String, val subType: String,
                                                    val fileExtensions: List[String]) extends WithOpenCharset {
    def params: Map[String, String] = Map.empty
    def withParams(params: Map[String, String]): WithOpenCharset with MediaType =
      customWithOpenCharset(mainType, subType, fileExtensions, params)
  }

  final class Multipart(val subType: String, val params: Map[String, String])
    extends WithOpenCharset with jm.MediaType.Multipart {
    val value = renderValue(mainType, subType, params)
    override def mainType = "multipart"
    override def isMultipart = true
    override def fileExtensions = Nil
    def withParams(params: Map[String, String]): MediaType.Multipart = new MediaType.Multipart(subType, params)
    def withBoundary(boundary: String): MediaType.Multipart =
      withParams(if (boundary.isEmpty) params - "boundary" else params.updated("boundary", boundary))
  }
}

object MediaTypes extends ObjectRegistry[(String, String), MediaType] {
  private[this] var extensionMap = Map.empty[String, MediaType]

  def forExtension(ext: String): Option[MediaType] = extensionMap.get(ext.toRootLowerCase)

  private def register[T <: MediaType](mediaType: T): T = {
    def registerFileExtension(ext: String): Unit = {
      val lcExt = ext.toRootLowerCase
      require(!extensionMap.contains(lcExt), s"Extension '$ext' clash: media-types '${extensionMap(lcExt)}' and '$mediaType'")
      extensionMap = extensionMap.updated(lcExt, mediaType)
    }
    mediaType.fileExtensions.foreach(registerFileExtension)
    register(mediaType.mainType.toRootLowerCase -> mediaType.subType.toRootLowerCase, mediaType)
  }

  import MediaType._  
  
  /////////////////////////// PREDEFINED MEDIA-TYPE DEFINITION ////////////////////////////
  // format: OFF
  private final val compressible = true    // compile-time constant
  private final val uncompressible = false // compile-time constant

  private def abin(st: String, c: Boolean, fe: String*)       = register(applicationBinary(st, c, fe: _*))
  private def awfc(st: String, cs: HttpCharset, fe: String*)  = register(applicationWithFixedCharset(st, cs, fe: _*))
  private def awoc(st: String, fe: String*)                   = register(applicationWithOpenCharset(st, fe: _*))
  private def aud(st: String, c: Boolean, fe: String*)        = register(audio(st, c, fe: _*))
  private def img(st: String, c: Boolean, fe: String*)        = register(image(st, c, fe: _*))
  private def msg(st: String, fe: String*)                    = register(message(st, compressible, fe: _*))
  private def txt(st: String, fe: String*)                    = register(text(st, fe: _*))
  private def vid(st: String, fe: String*)                    = register(video(st, compressible, fe: _*))

  // dummy value currently only used by ContentType.NoContentType
  private[http] val NoMediaType = MediaType.customBinary("none", "none", compressible = false)

  val `application/atom+xml`                                                      = awoc("atom+xml", "atom")
  val `application/base64`                                                        = awoc("base64", "mm", "mme")
  val `application/excel`                                                         = abin("excel", uncompressible, "xl", "xla", "xlb", "xlc", "xld", "xlk", "xll", "xlm", "xls", "xlt", "xlv", "xlw")
  val `application/font-woff`                                                     = abin("font-woff", uncompressible, "woff")
  val `application/gnutar`                                                        = abin("gnutar", uncompressible, "tgz")
  val `application/java-archive`                                                  = abin("java-archive", uncompressible, "jar", "war", "ear")
  val `application/javascript`                                                    = awoc("javascript", "js")
  val `application/json`                                                          = awfc("json", HttpCharsets.`UTF-8`, "json")
  val `application/json-patch+json`                                               = awfc("json-patch+json", HttpCharsets.`UTF-8`)
  val `application/lha`                                                           = abin("lha", uncompressible, "lha")
  val `application/lzx`                                                           = abin("lzx", uncompressible, "lzx")
  val `application/mspowerpoint`                                                  = abin("mspowerpoint", uncompressible, "pot", "pps", "ppt", "ppz")
  val `application/msword`                                                        = abin("msword", uncompressible, "doc", "dot", "w6w", "wiz", "word", "wri")
  val `application/octet-stream`                                                  = abin("octet-stream", uncompressible, "a", "bin", "class", "dump", "exe", "lhx", "lzh", "o", "psd", "saveme", "zoo")
  val `application/pdf`                                                           = abin("pdf", uncompressible, "pdf")
  val `application/postscript`                                                    = abin("postscript", compressible, "ai", "eps", "ps")
  val `application/rss+xml`                                                       = awoc("rss+xml", "rss")
  val `application/soap+xml`                                                      = awoc("soap+xml")
  val `application/vnd.api+json`                                                  = awfc("vnd.api+json", HttpCharsets.`UTF-8`)
  val `application/vnd.google-earth.kml+xml`                                      = awoc("vnd.google-earth.kml+xml", "kml")
  val `application/vnd.google-earth.kmz`                                          = abin("vnd.google-earth.kmz", uncompressible, "kmz")
  val `application/vnd.ms-fontobject`                                             = abin("vnd.ms-fontobject", compressible, "eot")
  val `application/vnd.oasis.opendocument.chart`                                  = abin("vnd.oasis.opendocument.chart", compressible, "odc")
  val `application/vnd.oasis.opendocument.database`                               = abin("vnd.oasis.opendocument.database", compressible, "odb")
  val `application/vnd.oasis.opendocument.formula`                                = abin("vnd.oasis.opendocument.formula", compressible, "odf")
  val `application/vnd.oasis.opendocument.graphics`                               = abin("vnd.oasis.opendocument.graphics", compressible, "odg")
  val `application/vnd.oasis.opendocument.image`                                  = abin("vnd.oasis.opendocument.image", compressible, "odi")
  val `application/vnd.oasis.opendocument.presentation`                           = abin("vnd.oasis.opendocument.presentation", compressible, "odp")
  val `application/vnd.oasis.opendocument.spreadsheet`                            = abin("vnd.oasis.opendocument.spreadsheet", compressible, "ods")
  val `application/vnd.oasis.opendocument.text`                                   = abin("vnd.oasis.opendocument.text", compressible, "odt")
  val `application/vnd.oasis.opendocument.text-master`                            = abin("vnd.oasis.opendocument.text-master", compressible, "odm", "otm")
  val `application/vnd.oasis.opendocument.text-web`                               = abin("vnd.oasis.opendocument.text-web", compressible, "oth")
  val `application/vnd.openxmlformats-officedocument.presentationml.presentation` = abin("vnd.openxmlformats-officedocument.presentationml.presentation", compressible, "pptx")
  val `application/vnd.openxmlformats-officedocument.presentationml.slide`        = abin("vnd.openxmlformats-officedocument.presentationml.slide", compressible, "sldx")
  val `application/vnd.openxmlformats-officedocument.presentationml.slideshow`    = abin("vnd.openxmlformats-officedocument.presentationml.slideshow", compressible, "ppsx")
  val `application/vnd.openxmlformats-officedocument.presentationml.template`     = abin("vnd.openxmlformats-officedocument.presentationml.template", compressible, "potx")
  val `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`         = abin("vnd.openxmlformats-officedocument.spreadsheetml.sheet", compressible, "xlsx")
  val `application/vnd.openxmlformats-officedocument.spreadsheetml.template`      = abin("vnd.openxmlformats-officedocument.spreadsheetml.template", compressible, "xltx")
  val `application/vnd.openxmlformats-officedocument.wordprocessingml.document`   = abin("vnd.openxmlformats-officedocument.wordprocessingml.document", compressible, "docx")
  val `application/vnd.openxmlformats-officedocument.wordprocessingml.template`   = abin("vnd.openxmlformats-officedocument.wordprocessingml.template", compressible, "dotx")
  val `application/x-7z-compressed`                                               = abin("x-7z-compressed", uncompressible, "7z", "s7z")
  val `application/x-ace-compressed`                                              = abin("x-ace-compressed", uncompressible, "ace")
  val `application/x-apple-diskimage`                                             = abin("x-apple-diskimage", uncompressible, "dmg")
  val `application/x-arc-compressed`                                              = abin("x-arc-compressed", uncompressible, "arc")
  val `application/x-bzip`                                                        = abin("x-bzip", uncompressible, "bz")
  val `application/x-bzip2`                                                       = abin("x-bzip2", uncompressible, "boz", "bz2")
  val `application/x-chrome-extension`                                            = abin("x-chrome-extension", uncompressible, "crx")
  val `application/x-compress`                                                    = abin("x-compress", uncompressible, "z")
  val `application/x-compressed`                                                  = abin("x-compressed", uncompressible, "gz")
  val `application/x-debian-package`                                              = abin("x-debian-package", compressible, "deb")
  val `application/x-dvi`                                                         = abin("x-dvi", compressible, "dvi")
  val `application/x-font-truetype`                                               = abin("x-font-truetype", compressible, "ttf")
  val `application/x-font-opentype`                                               = abin("x-font-opentype", compressible, "otf")
  val `application/x-gtar`                                                        = abin("x-gtar", uncompressible, "gtar")
  val `application/x-gzip`                                                        = abin("x-gzip", uncompressible, "gzip")
  val `application/x-latex`                                                       = awoc("x-latex", "latex", "ltx")
  val `application/x-rar-compressed`                                              = abin("x-rar-compressed", uncompressible, "rar")
  val `application/x-redhat-package-manager`                                      = abin("x-redhat-package-manager", uncompressible, "rpm")
  val `application/x-shockwave-flash`                                             = abin("x-shockwave-flash", uncompressible, "swf")
  val `application/x-tar`                                                         = abin("x-tar", compressible, "tar")
  val `application/x-tex`                                                         = abin("x-tex", compressible, "tex")
  val `application/x-texinfo`                                                     = abin("x-texinfo", compressible, "texi", "texinfo")
  val `application/x-vrml`                                                        = awoc("x-vrml", "vrml")
  val `application/x-www-form-urlencoded`                                         = awoc("x-www-form-urlencoded")
  val `application/x-x509-ca-cert`                                                = abin("x-x509-ca-cert", compressible, "der")
  val `application/x-xpinstall`                                                   = abin("x-xpinstall", uncompressible, "xpi")
  val `application/xhtml+xml`                                                     = awoc("xhtml+xml")
  val `application/xml-dtd`                                                       = awoc("xml-dtd")
  val `application/xml`                                                           = awoc("xml")
  val `application/zip`                                                           = abin("zip", uncompressible, "zip")

  val `audio/aiff`        = aud("aiff", compressible, "aif", "aifc", "aiff")
  val `audio/basic`       = aud("basic", compressible, "au", "snd")
  val `audio/midi`        = aud("midi", compressible, "mid", "midi", "kar")
  val `audio/mod`         = aud("mod", uncompressible, "mod")
  val `audio/mpeg`        = aud("mpeg", uncompressible, "m2a", "mp2", "mp3", "mpa", "mpga")
  val `audio/ogg`         = aud("ogg", uncompressible, "oga", "ogg")
  val `audio/voc`         = aud("voc", uncompressible, "voc")
  val `audio/vorbis`      = aud("vorbis", uncompressible, "vorbis")
  val `audio/voxware`     = aud("voxware", uncompressible, "vox")
  val `audio/wav`         = aud("wav", compressible, "wav")
  val `audio/x-realaudio` = aud("x-pn-realaudio", uncompressible, "ra", "ram", "rmm", "rmp")
  val `audio/x-psid`      = aud("x-psid", compressible, "sid")
  val `audio/xm`          = aud("xm", uncompressible, "xm")
  val `audio/webm`        = aud("webm", uncompressible)

  val `image/gif`         = img("gif", uncompressible, "gif")
  val `image/jpeg`        = img("jpeg", uncompressible, "jpe", "jpeg", "jpg")
  val `image/pict`        = img("pict", compressible, "pic", "pict")
  val `image/png`         = img("png", uncompressible, "png")
  val `image/svg+xml`     = img("svg+xml", compressible, "svg")
  val `image/tiff`        = img("tiff", compressible, "tif", "tiff")
  val `image/x-icon`      = img("x-icon", compressible, "ico")
  val `image/x-ms-bmp`    = img("x-ms-bmp", compressible, "bmp")
  val `image/x-pcx`       = img("x-pcx", compressible, "pcx")
  val `image/x-pict`      = img("x-pict", compressible, "pct")
  val `image/x-quicktime` = img("x-quicktime", uncompressible, "qif", "qti", "qtif")
  val `image/x-rgb`       = img("x-rgb", compressible, "rgb")
  val `image/x-xbitmap`   = img("x-xbitmap", compressible, "xbm")
  val `image/x-xpixmap`   = img("x-xpixmap", compressible, "xpm")
  val `image/webp`        = img("webp", uncompressible, "webp")

  val `message/http`            = msg("http")
  val `message/delivery-status` = msg("delivery-status")
  val `message/rfc822`          = msg("rfc822", "eml", "mht", "mhtml", "mime")

  object multipart {
    def mixed      (params: Map[String, String]) = new MediaType.Multipart("mixed", params)
    def alternative(params: Map[String, String]) = new MediaType.Multipart("alternative", params)
    def related    (params: Map[String, String]) = new MediaType.Multipart("related", params)
    def `form-data`(params: Map[String, String]) = new MediaType.Multipart("form-data", params)
    def signed     (params: Map[String, String]) = new MediaType.Multipart("signed", params)
    def encrypted  (params: Map[String, String]) = new MediaType.Multipart("encrypted", params)
    def byteRanges (params: Map[String, String]) = new MediaType.Multipart("byteranges", params)
  }

  val `multipart/mixed`       = multipart.mixed(Map.empty)
  val `multipart/alternative` = multipart.alternative(Map.empty)
  val `multipart/related`     = multipart.related(Map.empty)
  val `multipart/form-data`   = multipart.`form-data`(Map.empty)
  val `multipart/signed`      = multipart.signed(Map.empty)
  val `multipart/encrypted`   = multipart.encrypted(Map.empty)
  val `multipart/byteranges`  = multipart.byteRanges(Map.empty)

  val `text/asp`                  = txt("asp", "asp")
  val `text/cache-manifest`       = txt("cache-manifest", "manifest")
  val `text/calendar`             = txt("calendar", "ics", "icz")
  val `text/css`                  = txt("css", "css")
  val `text/csv`                  = txt("csv", "csv")
  val `text/html`                 = txt("html", "htm", "html", "htmls", "htx")
  val `text/mcf`                  = txt("mcf", "mcf")
  val `text/plain`                = txt("plain", "conf", "text", "txt", "properties")
  val `text/richtext`             = txt("richtext", "rtf", "rtx")
  val `text/tab-separated-values` = txt("tab-separated-values", "tsv")
  val `text/uri-list`             = txt("uri-list", "uni", "unis", "uri", "uris")
  val `text/vnd.wap.wml`          = txt("vnd.wap.wml", "wml")
  val `text/vnd.wap.wmlscript`    = txt("vnd.wap.wmlscript", "wmls")
  val `text/x-asm`                = txt("x-asm", "asm", "s")
  val `text/x-c`                  = txt("x-c", "c", "cc", "cpp")
  val `text/x-component`          = txt("x-component", "htc")
  val `text/x-h`                  = txt("x-h", "h", "hh")
  val `text/x-java-source`        = txt("x-java-source", "jav", "java")
  val `text/x-pascal`             = txt("x-pascal", "p")
  val `text/x-script`             = txt("x-script", "hlb")
  val `text/x-scriptcsh`          = txt("x-scriptcsh", "csh")
  val `text/x-scriptelisp`        = txt("x-scriptelisp", "el")
  val `text/x-scriptksh`          = txt("x-scriptksh", "ksh")
  val `text/x-scriptlisp`         = txt("x-scriptlisp", "lsp")
  val `text/x-scriptperl`         = txt("x-scriptperl", "pl")
  val `text/x-scriptperl-module`  = txt("x-scriptperl-module", "pm")
  val `text/x-scriptphyton`       = txt("x-scriptphyton", "py")
  val `text/x-scriptrexx`         = txt("x-scriptrexx", "rexx")
  val `text/x-scriptscheme`       = txt("x-scriptscheme", "scm")
  val `text/x-scriptsh`           = txt("x-scriptsh", "sh")
  val `text/x-scripttcl`          = txt("x-scripttcl", "tcl")
  val `text/x-scripttcsh`         = txt("x-scripttcsh", "tcsh")
  val `text/x-scriptzsh`          = txt("x-scriptzsh", "zsh")
  val `text/x-server-parsed-html` = txt("x-server-parsed-html", "shtml", "ssi")
  val `text/x-setext`             = txt("x-setext", "etx")
  val `text/x-sgml`               = txt("x-sgml", "sgm", "sgml")
  val `text/x-speech`             = txt("x-speech", "spc", "talk")
  val `text/x-uuencode`           = txt("x-uuencode", "uu", "uue")
  val `text/x-vcalendar`          = txt("x-vcalendar", "vcs")
  val `text/x-vcard`              = txt("x-vcard", "vcf", "vcard")
  val `text/xml`                  = txt("xml", "xml")

  val `video/avs-video`     = vid("avs-video", "avs")
  val `video/divx`          = vid("divx", "divx")
  val `video/gl`            = vid("gl", "gl")
  val `video/mp4`           = vid("mp4", "mp4")
  val `video/mpeg`          = vid("mpeg", "m1v", "m2v", "mpe", "mpeg", "mpg")
  val `video/ogg`           = vid("ogg", "ogv")
  val `video/quicktime`     = vid("quicktime", "moov", "mov", "qt")
  val `video/x-dv`          = vid("x-dv", "dif", "dv")
  val `video/x-flv`         = vid("x-flv", "flv")
  val `video/x-motion-jpeg` = vid("x-motion-jpeg", "mjpg")
  val `video/x-ms-asf`      = vid("x-ms-asf", "asf")
  val `video/x-msvideo`     = vid("x-msvideo", "avi")
  val `video/x-sgi-movie`   = vid("x-sgi-movie", "movie", "mv")
  val `video/webm`          = vid("webm", "webm")
  // format: ON
}