/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

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
  import MediaType.Compressibility

  def fileExtensions: List[String]
  def params: Map[String, String]
  def comp: Compressibility

  override def isApplication: Boolean = false
  override def isAudio: Boolean = false
  override def isImage: Boolean = false
  override def isMessage: Boolean = false
  override def isMultipart: Boolean = false
  override def isText: Boolean = false
  override def isVideo: Boolean = false

  def withParams(params: Map[String, String]): MediaType
  def withComp(comp: Compressibility): MediaType
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
  def isCompressible: Boolean = comp.compressible
}

object MediaType {

  def applicationBinary(subType: String, comp: Compressibility, fileExtensions: String*): Binary =
    new Binary("application/" + subType, "application", subType, comp, fileExtensions.toList) {
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

  def audio(subType: String, comp: Compressibility, fileExtensions: String*): Binary =
    new Binary("audio/" + subType, "audio", subType, comp, fileExtensions.toList) {
      override def isAudio = true
    }

  def image(subType: String, comp: Compressibility, fileExtensions: String*): Binary =
    new Binary("image/" + subType, "image", subType, comp, fileExtensions.toList) {
      override def isImage = true
    }

  def message(subType: String, comp: Compressibility, fileExtensions: String*): Binary =
    new Binary("message/" + subType, "message", subType, comp, fileExtensions.toList) {
      override def isMessage = true
    }

  def text(subType: String, fileExtensions: String*): WithOpenCharset =
    new NonMultipartWithOpenCharset("text/" + subType, "text", subType, fileExtensions.toList) {
      override def isText = true
    }

  def video(subType: String, comp: Compressibility, fileExtensions: String*): Binary =
    new Binary("video/" + subType, "video", subType, comp, fileExtensions.toList) {
      override def isVideo = true
    }

  def customBinary(mainType: String, subType: String, comp: Compressibility, fileExtensions: List[String] = Nil,
                   params: Map[String, String] = Map.empty, allowArbitrarySubtypes: Boolean = false): Binary = {
    require(mainType != "multipart", "Cannot create a MediaType.Multipart here, use `customMultipart` instead!")
    require(allowArbitrarySubtypes || subType != "*", "Cannot create a MediaRange here, use `MediaRange.custom` instead!")
    val _params = params
    new Binary(renderValue(mainType, subType, params), mainType, subType, comp, fileExtensions) {
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

  def custom(value: String, binary: Boolean, comp: Compressibility = Compressible,
             fileExtensions: List[String] = Nil): MediaType = {
    val parts = value.split('/')
    require(parts.length == 2, s"`$value` is not a valid media-type. It must consist of two parts separated by '/'.")
    if (binary) customBinary(parts(0), parts(1), comp, fileExtensions)
    else customWithOpenCharset(parts(0), parts(1), fileExtensions)
  }

  /**
   * Tries to parse a `MediaType` value from the given String.
   * Returns `Right(mediaType)` if successful and `Left(errors)` otherwise.
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

  sealed abstract class Binary(val value: String, val mainType: String, val subType: String, val comp: Compressibility,
                               val fileExtensions: List[String]) extends MediaType with jm.MediaType.Binary {
    def binary = true
    def params: Map[String, String] = Map.empty
    def withParams(params: Map[String, String]): Binary with MediaType =
      customBinary(mainType, subType, comp, fileExtensions, params)
    def withComp(comp: Compressibility): Binary with MediaType =
      customBinary(mainType, subType, comp, fileExtensions, params)

    /**
     * JAVA API
     */
    def toContentType: ContentType.Binary = ContentType(this)
  }

  sealed abstract class NonBinary extends MediaType with jm.MediaType.NonBinary {
    def binary = false
    def comp = Compressible
    def withComp(comp: Compressibility): Binary with MediaType =
      customBinary(mainType, subType, comp, fileExtensions, params)
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

  sealed abstract class Compressibility(val compressible: Boolean)
  case object Compressible extends Compressibility(compressible = true)
  case object NotCompressible extends Compressibility(compressible = false)
  case object Gzipped extends Compressibility(compressible = false)
}

object MediaTypes extends ObjectRegistry[(String, String), MediaType] {
  private[this] var extensionMap = Map.empty[String, MediaType]

  def forExtensionOption(ext: String): Option[MediaType] = extensionMap.get(ext.toLowerCase)
  def forExtension(ext: String): MediaType = extensionMap.getOrElse(ext.toLowerCase, `application/octet-stream`)

  private def registerFileExtensions[T <: MediaType](mediaType: T): T = {
    mediaType.fileExtensions.foreach { ext ⇒
      val lcExt = ext.toLowerCase
      require(!extensionMap.contains(lcExt), s"Extension '$ext' clash: media-types '${extensionMap(lcExt)}' and '$mediaType'")
      extensionMap = extensionMap.updated(lcExt, mediaType)
    }
    mediaType
  }

  private def register[T <: MediaType](mediaType: T): T = {
    registerFileExtensions(mediaType)
    register(mediaType.mainType.toRootLowerCase -> mediaType.subType.toRootLowerCase, mediaType)
  }

  import MediaType._  
  
  /////////////////////////// PREDEFINED MEDIA-TYPE DEFINITION ////////////////////////////
  // format: OFF

  private def abin(st: String, c: Compressibility, fe: String*) = register(applicationBinary(st, c, fe: _*))
  private def awfc(st: String, cs: HttpCharset, fe: String*)    = register(applicationWithFixedCharset(st, cs, fe: _*))
  private def awoc(st: String, fe: String*)                     = register(applicationWithOpenCharset(st, fe: _*))
  private def aud(st: String, c: Compressibility, fe: String*)  = register(audio(st, c, fe: _*))
  private def img(st: String, c: Compressibility, fe: String*)  = register(image(st, c, fe: _*))
  private def msg(st: String, fe: String*)                      = register(message(st, Compressible, fe: _*))
  private def txt(st: String, fe: String*)                      = register(text(st, fe: _*))
  private def vid(st: String, fe: String*)                      = register(video(st, NotCompressible, fe: _*))

  // dummy value currently only used by ContentType.NoContentType
  private[http] val NoMediaType = MediaType.customBinary("none", "none", comp = NotCompressible)

  val `application/atom+xml`                                                      = awoc("atom+xml", "atom")
  val `application/base64`                                                        = awoc("base64", "mm", "mme")
  val `application/excel`                                                         = abin("excel", NotCompressible, "xl", "xla", "xlb", "xlc", "xld", "xlk", "xll", "xlm", "xls", "xlt", "xlv", "xlw")
  val `application/font-woff`                                                     = abin("font-woff", NotCompressible, "woff")
  val `application/gnutar`                                                        = abin("gnutar", NotCompressible, "tgz")
  val `application/java-archive`                                                  = abin("java-archive", NotCompressible, "jar", "war", "ear")
  val `application/javascript`                                                    = awoc("javascript", "js")
  val `application/json`                                                          = awfc("json", HttpCharsets.`UTF-8`, "json")
  val `application/json-patch+json`                                               = awfc("json-patch+json", HttpCharsets.`UTF-8`)
  val `application/lha`                                                           = abin("lha", NotCompressible, "lha")
  val `application/lzx`                                                           = abin("lzx", NotCompressible, "lzx")
  val `application/mspowerpoint`                                                  = abin("mspowerpoint", NotCompressible, "pot", "pps", "ppt", "ppz")
  val `application/msword`                                                        = abin("msword", NotCompressible, "doc", "dot", "w6w", "wiz", "word", "wri")
  val `application/octet-stream`                                                  = abin("octet-stream", NotCompressible, "a", "bin", "class", "dump", "exe", "lhx", "lzh", "o", "psd", "saveme", "zoo")
  val `application/pdf`                                                           = abin("pdf", NotCompressible, "pdf")
  val `application/postscript`                                                    = abin("postscript", Compressible, "ai", "eps", "ps")
  val `application/rss+xml`                                                       = awoc("rss+xml", "rss")
  val `application/soap+xml`                                                      = awoc("soap+xml")
  val `application/vnd.api+json`                                                  = awfc("vnd.api+json", HttpCharsets.`UTF-8`)
  val `application/vnd.google-earth.kml+xml`                                      = awoc("vnd.google-earth.kml+xml", "kml")
  val `application/vnd.google-earth.kmz`                                          = abin("vnd.google-earth.kmz", NotCompressible, "kmz")
  val `application/vnd.ms-fontobject`                                             = abin("vnd.ms-fontobject", Compressible, "eot")
  val `application/vnd.oasis.opendocument.chart`                                  = abin("vnd.oasis.opendocument.chart", Compressible, "odc")
  val `application/vnd.oasis.opendocument.database`                               = abin("vnd.oasis.opendocument.database", Compressible, "odb")
  val `application/vnd.oasis.opendocument.formula`                                = abin("vnd.oasis.opendocument.formula", Compressible, "odf")
  val `application/vnd.oasis.opendocument.graphics`                               = abin("vnd.oasis.opendocument.graphics", Compressible, "odg")
  val `application/vnd.oasis.opendocument.image`                                  = abin("vnd.oasis.opendocument.image", Compressible, "odi")
  val `application/vnd.oasis.opendocument.presentation`                           = abin("vnd.oasis.opendocument.presentation", Compressible, "odp")
  val `application/vnd.oasis.opendocument.spreadsheet`                            = abin("vnd.oasis.opendocument.spreadsheet", Compressible, "ods")
  val `application/vnd.oasis.opendocument.text`                                   = abin("vnd.oasis.opendocument.text", Compressible, "odt")
  val `application/vnd.oasis.opendocument.text-master`                            = abin("vnd.oasis.opendocument.text-master", Compressible, "odm", "otm")
  val `application/vnd.oasis.opendocument.text-web`                               = abin("vnd.oasis.opendocument.text-web", Compressible, "oth")
  val `application/vnd.openxmlformats-officedocument.presentationml.presentation` = abin("vnd.openxmlformats-officedocument.presentationml.presentation", Compressible, "pptx")
  val `application/vnd.openxmlformats-officedocument.presentationml.slide`        = abin("vnd.openxmlformats-officedocument.presentationml.slide", Compressible, "sldx")
  val `application/vnd.openxmlformats-officedocument.presentationml.slideshow`    = abin("vnd.openxmlformats-officedocument.presentationml.slideshow", Compressible, "ppsx")
  val `application/vnd.openxmlformats-officedocument.presentationml.template`     = abin("vnd.openxmlformats-officedocument.presentationml.template", Compressible, "potx")
  val `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`         = abin("vnd.openxmlformats-officedocument.spreadsheetml.sheet", Compressible, "xlsx")
  val `application/vnd.openxmlformats-officedocument.spreadsheetml.template`      = abin("vnd.openxmlformats-officedocument.spreadsheetml.template", Compressible, "xltx")
  val `application/vnd.openxmlformats-officedocument.wordprocessingml.document`   = abin("vnd.openxmlformats-officedocument.wordprocessingml.document", Compressible, "docx")
  val `application/vnd.openxmlformats-officedocument.wordprocessingml.template`   = abin("vnd.openxmlformats-officedocument.wordprocessingml.template", Compressible, "dotx")
  val `application/x-7z-compressed`                                               = abin("x-7z-compressed", NotCompressible, "7z", "s7z")
  val `application/x-ace-compressed`                                              = abin("x-ace-compressed", NotCompressible, "ace")
  val `application/x-apple-diskimage`                                             = abin("x-apple-diskimage", NotCompressible, "dmg")
  val `application/x-arc-compressed`                                              = abin("x-arc-compressed", NotCompressible, "arc")
  val `application/x-bzip`                                                        = abin("x-bzip", NotCompressible, "bz")
  val `application/x-bzip2`                                                       = abin("x-bzip2", NotCompressible, "boz", "bz2")
  val `application/x-chrome-extension`                                            = abin("x-chrome-extension", NotCompressible, "crx")
  val `application/x-compress`                                                    = abin("x-compress", NotCompressible, "z")
  val `application/x-compressed`                                                  = abin("x-compressed", NotCompressible, "gz")
  val `application/x-debian-package`                                              = abin("x-debian-package", Compressible, "deb")
  val `application/x-dvi`                                                         = abin("x-dvi", Compressible, "dvi")
  val `application/x-font-truetype`                                               = abin("x-font-truetype", Compressible, "ttf")
  val `application/x-font-opentype`                                               = abin("x-font-opentype", Compressible, "otf")
  val `application/x-gtar`                                                        = abin("x-gtar", NotCompressible, "gtar")
  val `application/x-gzip`                                                        = abin("x-gzip", NotCompressible, "gzip")
  val `application/x-latex`                                                       = awoc("x-latex", "latex", "ltx")
  val `application/x-rar-compressed`                                              = abin("x-rar-compressed", NotCompressible, "rar")
  val `application/x-redhat-package-manager`                                      = abin("x-redhat-package-manager", NotCompressible, "rpm")
  val `application/x-shockwave-flash`                                             = abin("x-shockwave-flash", NotCompressible, "swf")
  val `application/x-tar`                                                         = abin("x-tar", Compressible, "tar")
  val `application/x-tex`                                                         = abin("x-tex", Compressible, "tex")
  val `application/x-texinfo`                                                     = abin("x-texinfo", Compressible, "texi", "texinfo")
  val `application/x-vrml`                                                        = awoc("x-vrml", "vrml")
  val `application/x-www-form-urlencoded`                                         = awoc("x-www-form-urlencoded")
  val `application/x-x509-ca-cert`                                                = abin("x-x509-ca-cert", Compressible, "der")
  val `application/x-xpinstall`                                                   = abin("x-xpinstall", NotCompressible, "xpi")
  val `application/xhtml+xml`                                                     = awoc("xhtml+xml")
  val `application/xml-dtd`                                                       = awoc("xml-dtd")
  val `application/xml`                                                           = awoc("xml")
  val `application/zip`                                                           = abin("zip", NotCompressible, "zip")

  val `audio/aiff`        = aud("aiff", Compressible, "aif", "aifc", "aiff")
  val `audio/basic`       = aud("basic", Compressible, "au", "snd")
  val `audio/midi`        = aud("midi", Compressible, "mid", "midi", "kar")
  val `audio/mod`         = aud("mod", NotCompressible, "mod")
  val `audio/mpeg`        = aud("mpeg", NotCompressible, "m2a", "mp2", "mp3", "mpa", "mpga")
  val `audio/ogg`         = aud("ogg", NotCompressible, "oga", "ogg")
  val `audio/voc`         = aud("voc", NotCompressible, "voc")
  val `audio/vorbis`      = aud("vorbis", NotCompressible, "vorbis")
  val `audio/voxware`     = aud("voxware", NotCompressible, "vox")
  val `audio/wav`         = aud("wav", Compressible, "wav")
  val `audio/x-realaudio` = aud("x-pn-realaudio", NotCompressible, "ra", "ram", "rmm", "rmp")
  val `audio/x-psid`      = aud("x-psid", Compressible, "sid")
  val `audio/xm`          = aud("xm", NotCompressible, "xm")
  val `audio/webm`        = aud("webm", NotCompressible)

  val `image/gif`         = img("gif", NotCompressible, "gif")
  val `image/jpeg`        = img("jpeg", NotCompressible, "jpe", "jpeg", "jpg")
  val `image/pict`        = img("pict", Compressible, "pic", "pict")
  val `image/png`         = img("png", NotCompressible, "png")
  val `image/svg+xml`     = img("svg+xml", Compressible, "svg")
  val `image/svgz`        = registerFileExtensions(image("svg+xml", Gzipped, "svgz"))
  val `image/tiff`        = img("tiff", Compressible, "tif", "tiff")
  val `image/x-icon`      = img("x-icon", Compressible, "ico")
  val `image/x-ms-bmp`    = img("x-ms-bmp", Compressible, "bmp")
  val `image/x-pcx`       = img("x-pcx", Compressible, "pcx")
  val `image/x-pict`      = img("x-pict", Compressible, "pct")
  val `image/x-quicktime` = img("x-quicktime", NotCompressible, "qif", "qti", "qtif")
  val `image/x-rgb`       = img("x-rgb", Compressible, "rgb")
  val `image/x-xbitmap`   = img("x-xbitmap", Compressible, "xbm")
  val `image/x-xpixmap`   = img("x-xpixmap", Compressible, "xpm")
  val `image/webp`        = img("webp", NotCompressible, "webp")

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
  val `text/calendar`             = txt("calendar", "ics")
  val `text/css`                  = txt("css", "css")
  val `text/csv`                  = txt("csv", "csv")
  val `text/html`                 = txt("html", "htm", "html", "htmls", "htx")
  val `text/markdown`             = txt("markdown", "markdown", "md")
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
