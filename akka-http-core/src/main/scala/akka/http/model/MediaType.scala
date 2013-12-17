package akka.http.model

import scala.language.implicitConversions

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import akka.http.rendering._

sealed abstract class MediaRange extends Renderable with WithQValue[MediaRange] {
  def value: String
  def mainType: String
  def parameters: Map[String, String]
  def qValue: Float
  def matches(mediaType: MediaType): Boolean
  def isApplication = false
  def isAudio = false
  def isImage = false
  def isMessage = false
  def isMultipart = false
  def isText = false
  def isVideo = false

  /**
   * Returns a copy of this instance with the parameters replaced by the given ones.
   * If the given map contains a "q" value the `qValue` member is (also) updated.
   */
  def withParameters(parameters: Map[String, String]): MediaRange

  /**
   * Constructs a `ContentTypeRange` from this instance and the given charset.
   */
  def withCharset(charsetRange: HttpCharsetRange): ContentTypeRange = ContentTypeRange(this, charsetRange)
}

object MediaRange {
  private[http] def splitOffQValue(parameters: Map[String, String], defaultQ: Float = 1.0f): (Map[String, String], Float) =
    parameters.get("q") match {
      case Some(x) ⇒ (parameters - "q") -> (try x.toFloat catch { case _: NumberFormatException ⇒ 1.0f })
      case None    ⇒ parameters -> defaultQ
    }

  private case class Custom(mainType: String, parameters: Map[String, String], qValue: Float)
    extends MediaRange with ValueRenderable {
    def matches(mediaType: MediaType) = mainType == "*" || mediaType.mainType == mainType
    def withParameters(parameters: Map[String, String]) = custom(mainType, parameters, qValue)
    def withQValue(qValue: Float) = if (qValue != this.qValue) custom(mainType, parameters, qValue) else this
    def render[R <: Rendering](r: R): r.type = {
      r ~~ mainType ~~ '/' ~~ '*'
      if (qValue < 1.0f) r ~~ ";q=" ~~ qValue
      if (parameters.nonEmpty) parameters foreach { case (k, v) ⇒ r ~~ ';' ~~ ' ' ~~ k ~~ '=' ~~# v }
      r
    }
    override def isApplication = mainType == "application"
    override def isAudio = mainType == "audio"
    override def isImage = mainType == "image"
    override def isMessage = mainType == "message"
    override def isMultipart = mainType == "multipart"
    override def isText = mainType == "text"
    override def isVideo = mainType == "video"
  }

  def custom(mainType: String, parameters: Map[String, String] = Map.empty, qValue: Float = 1.0f): MediaRange = {
    val (params, q) = splitOffQValue(parameters, qValue)
    Custom(mainType.toLowerCase, params, q)
  }

  case class One(mediaType: MediaType, qValue: Float) extends MediaRange with ValueRenderable {
    def mainType = mediaType.mainType
    def parameters = mediaType.parameters
    override def isApplication = mediaType.isApplication
    override def isAudio = mediaType.isApplication
    override def isImage = mediaType.isApplication
    override def isMessage = mediaType.isApplication
    override def isMultipart = mediaType.isApplication
    override def isText = mediaType.isApplication
    override def isVideo = mediaType.isApplication
    def matches(mediaType: MediaType) =
      this.mediaType.mainType == mediaType.mainType && this.mediaType.subType == mediaType.subType
    def withParameters(parameters: Map[String, String]) = copy(mediaType = mediaType.withParameters(parameters))
    def withQValue(qValue: Float) = copy(qValue = qValue)
    def render[R <: Rendering](r: R): r.type = if (qValue < 1.0f) r ~~ mediaType ~~ ";q=" ~~ qValue else r ~~ mediaType
  }

  implicit def apply(mediaType: MediaType): MediaRange = apply(mediaType, 1.0f)
  def apply(mediaType: MediaType, qValue: Float = 1.0f): MediaRange = One(mediaType, qValue)
}

object MediaRanges extends ObjectRegistry[String, MediaRange] {

  sealed abstract case class PredefinedMediaRange(value: String) extends MediaRange with LazyValueBytesRenderable {
    val mainType = value takeWhile (_ != '/')
    register(mainType, this)
    def parameters = Map.empty
    def qValue = 1.0f
    def withParameters(parameters: Map[String, String]) = MediaRange.custom(mainType, parameters)
    def withQValue(qValue: Float) = if (qValue != 1.0f) MediaRange.custom(mainType, parameters, qValue) else this
  }

  val `*/*` = new PredefinedMediaRange("*/*") {
    def matches(mediaType: MediaType) = true
  }
  val `application/*` = new PredefinedMediaRange("application/*") {
    def matches(mediaType: MediaType) = mediaType.isApplication
    override def isApplication: Boolean = true
  }
  val `audio/*` = new PredefinedMediaRange("audio/*") {
    def matches(mediaType: MediaType) = mediaType.isAudio
    override def isAudio: Boolean = true
  }
  val `image/*` = new PredefinedMediaRange("image/*") {
    def matches(mediaType: MediaType) = mediaType.isImage
    override def isImage: Boolean = true
  }
  val `message/*` = new PredefinedMediaRange("message/*") {
    def matches(mediaType: MediaType) = mediaType.isMessage
    override def isMessage: Boolean = true
  }
  val `multipart/*` = new PredefinedMediaRange("multipart/*") {
    def matches(mediaType: MediaType) = mediaType.isMultipart
    override def isMultipart: Boolean = true
  }
  val `text/*` = new PredefinedMediaRange("text/*") {
    def matches(mediaType: MediaType) = mediaType.isText
    override def isText: Boolean = true
  }
  val `video/*` = new PredefinedMediaRange("video/*") {
    def matches(mediaType: MediaType) = mediaType.isVideo
    override def isVideo: Boolean = true
  }
}

sealed abstract case class MediaType private[http] (value: String)(val mainType: String,
                                                                   val subType: String,
                                                                   val compressible: Boolean,
                                                                   val binary: Boolean,
                                                                   val fileExtensions: Seq[String],
                                                                   val parameters: Map[String, String])
  extends LazyValueBytesRenderable with WithQValue[MediaRange] {
  def isApplication = false
  def isAudio = false
  def isImage = false
  def isMessage = false
  def isMultipart = false
  def isText = false
  def isVideo = false

  /**
   * Returns a copy of this instance with the parameters replaced by the given ones.
   */
  def withParameters(parameters: Map[String, String]): MediaType

  /**
   * Constructs a `ContentType` from this instance and the given charset.
   */
  def withCharset(charset: HttpCharset): ContentType = ContentType(this, charset)

  def withQValue(qValue: Float): MediaRange = MediaRange(this, qValue.toFloat)
}

class MultipartMediaType private[http] (_value: String, _subType: String, _parameters: Map[String, String])
  extends MediaType(_value)("multipart", _subType, compressible = true, binary = true, Nil, _parameters) {
  override def isMultipart = true
  def withBoundary(boundary: String): MultipartMediaType = withParameters {
    if (boundary.isEmpty) parameters - "boundary" else parameters.updated("boundary", boundary)
  }
  def withParameters(parameters: Map[String, String]) = MediaTypes.multipart(subType, parameters)
}

sealed abstract class NonMultipartMediaType private[http] (_value: String, _mainType: String, _subType: String,
                                                           _compressible: Boolean, _binary: Boolean,
                                                           _fileExtensions: Seq[String],
                                                           _parameters: Map[String, String])
  extends MediaType(_value)(_mainType, _subType, _compressible, _binary, _fileExtensions, _parameters) {
  private[http] def this(mainType: String, subType: String, compressible: Boolean, binary: Boolean, fileExtensions: Seq[String]) =
    this(mainType + '/' + subType, mainType, subType, compressible, binary, fileExtensions, Map.empty)
  def withParameters(parameters: Map[String, String]) =
    MediaType.custom(mainType, subType, compressible, binary, fileExtensions, parameters)
}

object MediaType {
  /**
   * Allows the definition of custom media types. In order for your custom type to be properly used by the
   * HTTP layer you need to create an instance, register it via `MediaTypes.register` and use this instance in
   * your custom Marshallers and Unmarshallers.
   */
  def custom(mainType: String, subType: String, compressible: Boolean = false, binary: Boolean = false,
             fileExtensions: Seq[String] = Nil, parameters: Map[String, String] = Map.empty,
             allowArbitrarySubtypes: Boolean = false): MediaType = {
    require(mainType != "multipart", "Cannot create a MultipartMediaType here, use `multipart.apply` instead!")
    require(allowArbitrarySubtypes || subType != "*", "Cannot create a MediaRange here, use `MediaRange.custom` instead!")
    val r = new StringRendering ~~ mainType ~~ '/' ~~ subType
    if (parameters.nonEmpty) parameters foreach { case (k, v) ⇒ r ~~ ';' ~~ ' ' ~~ k ~~ '=' ~~# v }
    new NonMultipartMediaType(r.get, mainType, subType, compressible, binary, fileExtensions, parameters) {
      override def isApplication = mainType == "application"
      override def isAudio = mainType == "audio"
      override def isImage = mainType == "image"
      override def isMessage = mainType == "message"
      override def isText = mainType == "text"
      override def isVideo = mainType == "video"
    }
  }

  def custom(value: String): MediaType = {
    val parts = value.split('/')
    if (parts.length != 2) throw new IllegalArgumentException(value + " is not a valid media-type")
    custom(parts(0), parts(1))
  }
}

object MediaTypes extends ObjectRegistry[(String, String), MediaType] {

  private[this] val extensionMap = new AtomicReference(Map.empty[String, MediaType])

  def register(mediaType: MediaType): MediaType = {
    @tailrec def registerFileExtension(ext: String): Unit = {
      val lcExt = ext.toLowerCase
      val current = extensionMap.get
      require(!current.contains(lcExt), s"Extension '$ext' clash: media-types '${current(lcExt)}' and '$mediaType'")
      val updated = current.updated(lcExt, mediaType)
      if (!extensionMap.compareAndSet(current, updated)) registerFileExtension(ext)
    }
    mediaType.fileExtensions.foreach(registerFileExtension)
    register(mediaType.mainType.toLowerCase -> mediaType.subType.toLowerCase, mediaType)
  }

  def forExtension(ext: String): Option[MediaType] = extensionMap.get.get(ext.toLowerCase)

  private def app(subType: String, compressible: Boolean, binary: Boolean, fileExtensions: String*) = register {
    new NonMultipartMediaType("application", subType, compressible, binary, fileExtensions) {
      override def isApplication = true
    }
  }
  private def aud(subType: String, compressible: Boolean, fileExtensions: String*) = register {
    new NonMultipartMediaType("audio", subType, compressible, binary = true, fileExtensions) {
      override def isAudio = true
    }
  }
  private def img(subType: String, compressible: Boolean, binary: Boolean, fileExtensions: String*) = register {
    new NonMultipartMediaType("image", subType, compressible, binary, fileExtensions) {
      override def isImage = true
    }
  }
  private def msg(subType: String, fileExtensions: String*) = register {
    new NonMultipartMediaType("message", subType, compressible = true, binary = false, fileExtensions) {
      override def isMessage = true
    }
  }
  private def txt(subType: String, fileExtensions: String*) = register {
    new NonMultipartMediaType("text", subType, compressible = true, binary = false, fileExtensions) {
      override def isText = true
    }
  }
  private def vid(subType: String, fileExtensions: String*) = register {
    new NonMultipartMediaType("video", subType, compressible = false, binary = true, fileExtensions) {
      override def isVideo = true
    }
  }

  /////////////////////////// PREDEFINED MEDIA-TYPE DEFINITION ////////////////////////////
  // format: OFF
  private final val compressible = true    // compile-time constant
  private final val uncompressible = false // compile-time constant
  private final val binary = true          // compile-time constant
  private final val notBinary = false      // compile-time constant

  // dummy value currently only used by ContentType.NoContentType
  private[http] val NoMediaType = new NonMultipartMediaType("none", "none", false, false, Seq.empty) {}

  val `application/atom+xml`                                                      = app("atom+xml", compressible, notBinary, "atom")
  val `application/base64`                                                        = app("base64", compressible, binary, "mm", "mme")
  val `application/excel`                                                         = app("excel", uncompressible, binary, "xl", "xla", "xlb", "xlc", "xld", "xlk", "xll", "xlm", "xls", "xlt", "xlv", "xlw")
  val `application/font-woff`                                                     = app("font-woff", uncompressible, binary, "woff")
  val `application/gnutar`                                                        = app("gnutar", uncompressible, binary, "tgz")
  val `application/java-archive`                                                  = app("java-archive", uncompressible, binary, "jar", "war", "ear")
  val `application/javascript`                                                    = app("javascript", compressible, notBinary, "js")
  val `application/json`                                                          = app("json", compressible, binary, "json") // we treat JSON as binary, since its encoding is not variable but defined by RFC4627
  val `application/json-patch+json`                                               = app("json-patch+json", compressible, binary) // we treat JSON as binary, since its encoding is not variable but defined by RFC4627
  val `application/lha`                                                           = app("lha", uncompressible, binary, "lha")
  val `application/lzx`                                                           = app("lzx", uncompressible, binary, "lzx")
  val `application/mspowerpoint`                                                  = app("mspowerpoint", uncompressible, binary, "pot", "pps", "ppt", "ppz")
  val `application/msword`                                                        = app("msword", uncompressible, binary, "doc", "dot", "w6w", "wiz", "word", "wri")
  val `application/octet-stream`                                                  = app("octet-stream", uncompressible, binary, "a", "bin", "class", "dump", "exe", "lhx", "lzh", "o", "psd", "saveme", "zoo")
  val `application/pdf`                                                           = app("pdf", uncompressible, binary, "pdf")
  val `application/postscript`                                                    = app("postscript", compressible, binary, "ai", "eps", "ps")
  val `application/rss+xml`                                                       = app("rss+xml", compressible, notBinary, "rss")
  val `application/soap+xml`                                                      = app("soap+xml", compressible, notBinary)
  val `application/vnd.api+json`                                                  = app("vnd.api+json", compressible, binary) // we treat JSON as binary, since its encoding is not variable but defined by RFC4627
  val `application/vnd.google-earth.kml+xml`                                      = app("vnd.google-earth.kml+xml", compressible, notBinary, "kml")
  val `application/vnd.google-earth.kmz`                                          = app("vnd.google-earth.kmz", uncompressible, binary, "kmz")
  val `application/vnd.ms-fontobject`                                             = app("vnd.ms-fontobject", compressible, binary, "eot")
  val `application/vnd.oasis.opendocument.chart`                                  = app("vnd.oasis.opendocument.chart", compressible, binary, "odc")
  val `application/vnd.oasis.opendocument.database`                               = app("vnd.oasis.opendocument.database", compressible, binary, "odb")
  val `application/vnd.oasis.opendocument.formula`                                = app("vnd.oasis.opendocument.formula", compressible, binary, "odf")
  val `application/vnd.oasis.opendocument.graphics`                               = app("vnd.oasis.opendocument.graphics", compressible, binary, "odg")
  val `application/vnd.oasis.opendocument.image`                                  = app("vnd.oasis.opendocument.image", compressible, binary, "odi")
  val `application/vnd.oasis.opendocument.presentation`                           = app("vnd.oasis.opendocument.presentation", compressible, binary, "odp")
  val `application/vnd.oasis.opendocument.spreadsheet`                            = app("vnd.oasis.opendocument.spreadsheet", compressible, binary, "ods")
  val `application/vnd.oasis.opendocument.text`                                   = app("vnd.oasis.opendocument.text", compressible, binary, "odt")
  val `application/vnd.oasis.opendocument.text-master`                            = app("vnd.oasis.opendocument.text-master", compressible, binary, "odm", "otm")
  val `application/vnd.oasis.opendocument.text-web`                               = app("vnd.oasis.opendocument.text-web", compressible, binary, "oth")
  val `application/vnd.openxmlformats-officedocument.presentationml.presentation` = app("vnd.openxmlformats-officedocument.presentationml.presentation", compressible, binary, "pptx")
  val `application/vnd.openxmlformats-officedocument.presentationml.slide`        = app("vnd.openxmlformats-officedocument.presentationml.slide", compressible, binary, "sldx")
  val `application/vnd.openxmlformats-officedocument.presentationml.slideshow`    = app("vnd.openxmlformats-officedocument.presentationml.slideshow", compressible, binary, "ppsx")
  val `application/vnd.openxmlformats-officedocument.presentationml.template`     = app("vnd.openxmlformats-officedocument.presentationml.template", compressible, binary, "potx")
  val `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`         = app("vnd.openxmlformats-officedocument.spreadsheetml.sheet", compressible, binary, "xlsx")
  val `application/vnd.openxmlformats-officedocument.spreadsheetml.template`      = app("vnd.openxmlformats-officedocument.spreadsheetml.template", compressible, binary, "xltx")
  val `application/vnd.openxmlformats-officedocument.wordprocessingml.document`   = app("vnd.openxmlformats-officedocument.wordprocessingml.document", compressible, binary, "docx")
  val `application/vnd.openxmlformats-officedocument.wordprocessingml.template`   = app("vnd.openxmlformats-officedocument.wordprocessingml.template", compressible, binary, "dotx")
  val `application/x-7z-compressed`                                               = app("x-7z-compressed", uncompressible, binary, "7z", "s7z")
  val `application/x-ace-compressed`                                              = app("x-ace-compressed", uncompressible, binary, "ace")
  val `application/x-apple-diskimage`                                             = app("x-apple-diskimage", uncompressible, binary, "dmg")
  val `application/x-arc-compressed`                                              = app("x-arc-compressed", uncompressible, binary, "arc")
  val `application/x-bzip`                                                        = app("x-bzip", uncompressible, binary, "bz")
  val `application/x-bzip2`                                                       = app("x-bzip2", uncompressible, binary, "boz", "bz2")
  val `application/x-chrome-extension`                                            = app("x-chrome-extension", uncompressible, binary, "crx")
  val `application/x-compress`                                                    = app("x-compress", uncompressible, binary, "z")
  val `application/x-compressed`                                                  = app("x-compressed", uncompressible, binary, "gz")
  val `application/x-debian-package`                                              = app("x-debian-package", compressible, binary, "deb")
  val `application/x-dvi`                                                         = app("x-dvi", compressible, binary, "dvi")
  val `application/x-font-truetype`                                               = app("x-font-truetype", compressible, binary, "ttf")
  val `application/x-font-opentype`                                               = app("x-font-opentype", compressible, binary, "otf")
  val `application/x-gtar`                                                        = app("x-gtar", uncompressible, binary, "gtar")
  val `application/x-gzip`                                                        = app("x-gzip", uncompressible, binary, "gzip")
  val `application/x-latex`                                                       = app("x-latex", compressible, binary, "latex", "ltx")
  val `application/x-rar-compressed`                                              = app("x-rar-compressed", uncompressible, binary, "rar")
  val `application/x-redhat-package-manager`                                      = app("x-redhat-package-manager", uncompressible, binary, "rpm")
  val `application/x-shockwave-flash`                                             = app("x-shockwave-flash", uncompressible, binary, "swf")
  val `application/x-tar`                                                         = app("x-tar", compressible, binary, "tar")
  val `application/x-tex`                                                         = app("x-tex", compressible, binary, "tex")
  val `application/x-texinfo`                                                     = app("x-texinfo", compressible, binary, "texi", "texinfo")
  val `application/x-vrml`                                                        = app("x-vrml", compressible, notBinary, "vrml")
  val `application/x-www-form-urlencoded`                                         = app("x-www-form-urlencoded", compressible, notBinary)
  val `application/x-x509-ca-cert`                                                = app("x-x509-ca-cert", compressible, binary, "der")
  val `application/x-xpinstall`                                                   = app("x-xpinstall", uncompressible, binary, "xpi")
  val `application/xhtml+xml`                                                     = app("xhtml+xml", compressible, notBinary)
  val `application/xml-dtd`                                                       = app("xml-dtd", compressible, notBinary)
  val `application/xml`                                                           = app("xml", compressible, notBinary)
  val `application/zip`                                                           = app("zip", uncompressible, binary, "zip")

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

  val `image/gif`         = img("gif", uncompressible, binary, "gif")
  val `image/jpeg`        = img("jpeg", uncompressible, binary, "jpe", "jpeg", "jpg")
  val `image/pict`        = img("pict", compressible, binary, "pic", "pict")
  val `image/png`         = img("png", uncompressible, binary, "png")
  val `image/svg+xml`     = img("svg+xml", compressible, notBinary, "svg", "svgz")
  val `image/tiff`        = img("tiff", compressible, binary, "tif", "tiff")
  val `image/x-icon`      = img("x-icon", compressible, binary, "ico")
  val `image/x-ms-bmp`    = img("x-ms-bmp", compressible, binary, "bmp")
  val `image/x-pcx`       = img("x-pcx", compressible, binary, "pcx")
  val `image/x-pict`      = img("x-pict", compressible, binary, "pct")
  val `image/x-quicktime` = img("x-quicktime", uncompressible, binary, "qif", "qti", "qtif")
  val `image/x-rgb`       = img("x-rgb", compressible, binary, "rgb")
  val `image/x-xbitmap`   = img("x-xbitmap", compressible, binary, "xbm")
  val `image/x-xpixmap`   = img("x-xpixmap", compressible, binary, "xpm")

  val `message/http`            = msg("http")
  val `message/delivery-status` = msg("delivery-status")
  val `message/rfc822`          = msg("rfc822", "eml", "mht", "mhtml", "mime")

  object multipart {
    def apply(subType: String, parameters: Map[String, String]): MultipartMediaType = {
      require(subType != "*", "Cannot create a MediaRange here, use MediaRanges.`multipart/*` instead!")
      val r = new StringRendering ~~ "multipart/" ~~ subType
      if (parameters.nonEmpty) parameters foreach { case (k, v) ⇒ r ~~ ';' ~~ ' ' ~~ k ~~ '=' ~~# v }
      new MultipartMediaType(r.get, subType, parameters)
    }
    def mixed      (parameters: Map[String, String]) = apply("mixed", parameters)
    def alternative(parameters: Map[String, String]) = apply("alternative", parameters)
    def related    (parameters: Map[String, String]) = apply("related", parameters)
    def `form-data`(parameters: Map[String, String]) = apply("form-data", parameters)
    def signed     (parameters: Map[String, String]) = apply("signed", parameters)
    def encrypted  (parameters: Map[String, String]) = apply("encrypted", parameters)
  }

  val `multipart/mixed`       = multipart.mixed(Map.empty)
  val `multipart/alternative` = multipart.alternative(Map.empty)
  val `multipart/related`     = multipart.related(Map.empty)
  val `multipart/form-data`   = multipart.`form-data`(Map.empty)
  val `multipart/signed`      = multipart.signed(Map.empty)
  val `multipart/encrypted`   = multipart.encrypted(Map.empty)

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
