/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.MediaTypes$;

import java.util.Optional;

/**
 * Contains the set of predefined media-types.
 */
public final class MediaTypes {
    private MediaTypes() { }

    public static final MediaType.WithOpenCharset APPLICATION_ATOM_XML = akka.http.scaladsl.model.MediaTypes.application$divatom$plusxml();
    public static final MediaType.WithOpenCharset APPLICATION_BASE64 = akka.http.scaladsl.model.MediaTypes.application$divbase64();
    public static final MediaType.Binary APPLICATION_EXCEL = akka.http.scaladsl.model.MediaTypes.application$divexcel();
    public static final MediaType.Binary APPLICATION_FONT_WOFF = akka.http.scaladsl.model.MediaTypes.application$divfont$minuswoff();
    public static final MediaType.Binary APPLICATION_GNUTAR = akka.http.scaladsl.model.MediaTypes.application$divgnutar();
    public static final MediaType.Binary APPLICATION_JAVA_ARCHIVE = akka.http.scaladsl.model.MediaTypes.application$divjava$minusarchive();
    public static final MediaType.WithOpenCharset APPLICATION_JAVASCRIPT = akka.http.scaladsl.model.MediaTypes.application$divjavascript();
    public static final MediaType.WithFixedCharset APPLICATION_JSON = akka.http.scaladsl.model.MediaTypes.application$divjson();
    public static final MediaType.WithFixedCharset APPLICATION_JSON_PATCH_JSON = akka.http.scaladsl.model.MediaTypes.application$divjson$minuspatch$plusjson();
    public static final MediaType.Binary APPLICATION_LHA = akka.http.scaladsl.model.MediaTypes.application$divlha();
    public static final MediaType.Binary APPLICATION_LZX = akka.http.scaladsl.model.MediaTypes.application$divlzx();
    public static final MediaType.Binary APPLICATION_MSPOWERPOINT = akka.http.scaladsl.model.MediaTypes.application$divmspowerpoint();
    public static final MediaType.Binary APPLICATION_MSWORD = akka.http.scaladsl.model.MediaTypes.application$divmsword();
    public static final MediaType.Binary APPLICATION_OCTET_STREAM = akka.http.scaladsl.model.MediaTypes.application$divoctet$minusstream();
    public static final MediaType.Binary APPLICATION_PDF = akka.http.scaladsl.model.MediaTypes.application$divpdf();
    public static final MediaType.Binary APPLICATION_POSTSCRIPT = akka.http.scaladsl.model.MediaTypes.application$divpostscript();
    public static final MediaType.WithOpenCharset APPLICATION_RSS_XML = akka.http.scaladsl.model.MediaTypes.application$divrss$plusxml();
    public static final MediaType.WithOpenCharset APPLICATION_SOAP_XML = akka.http.scaladsl.model.MediaTypes.application$divsoap$plusxml();
    public static final MediaType.WithFixedCharset APPLICATION_VND_API_JSON = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eapi$plusjson();
    public static final MediaType.WithOpenCharset APPLICATION_VND_GOOGLE_EARTH_KML_XML = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Egoogle$minusearth$u002Ekml$plusxml();
    public static final MediaType.Binary APPLICATION_VND_GOOGLE_EARTH_KMZ = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Egoogle$minusearth$u002Ekmz();
    public static final MediaType.Binary APPLICATION_VND_MS_FONTOBJECT = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Ems$minusfontobject();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_CHART = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Echart();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_DATABASE = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Edatabase();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_FORMULA = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Eformula();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_GRAPHICS = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Egraphics();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_IMAGE = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Eimage();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Epresentation();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Espreadsheet();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Etext();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT_MASTER = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Etext$minusmaster();
    public static final MediaType.Binary APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT_WEB = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Etext$minusweb();
    public static final MediaType.Binary APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_PRESENTATION = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Epresentationml$u002Epresentation();
    public static final MediaType.Binary APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_SLIDE = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Epresentationml$u002Eslide();
    public static final MediaType.Binary APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_SLIDESHOW = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Epresentationml$u002Eslideshow();
    public static final MediaType.Binary APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_TEMPLATE = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Epresentationml$u002Etemplate();
    public static final MediaType.Binary APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_SPREADSHEETML_SHEET = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Espreadsheetml$u002Esheet();
    public static final MediaType.Binary APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_SPREADSHEETML_TEMPLATE = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Espreadsheetml$u002Etemplate();
    public static final MediaType.Binary APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_WORDPROCESSINGML_DOCUMENT = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Ewordprocessingml$u002Edocument();
    public static final MediaType.Binary APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_WORDPROCESSINGML_TEMPLATE = akka.http.scaladsl.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Ewordprocessingml$u002Etemplate();
    public static final MediaType.Binary APPLICATION_X_7Z_COMPRESSED = akka.http.scaladsl.model.MediaTypes.application$divx$minus7z$minuscompressed();
    public static final MediaType.Binary APPLICATION_X_ACE_COMPRESSED = akka.http.scaladsl.model.MediaTypes.application$divx$minusace$minuscompressed();
    public static final MediaType.Binary APPLICATION_X_APPLE_DISKIMAGE = akka.http.scaladsl.model.MediaTypes.application$divx$minusapple$minusdiskimage();
    public static final MediaType.Binary APPLICATION_X_ARC_COMPRESSED = akka.http.scaladsl.model.MediaTypes.application$divx$minusarc$minuscompressed();
    public static final MediaType.Binary APPLICATION_X_BZIP = akka.http.scaladsl.model.MediaTypes.application$divx$minusbzip();
    public static final MediaType.Binary APPLICATION_X_BZIP2 = akka.http.scaladsl.model.MediaTypes.application$divx$minusbzip2();
    public static final MediaType.Binary APPLICATION_X_CHROME_EXTENSION = akka.http.scaladsl.model.MediaTypes.application$divx$minuschrome$minusextension();
    public static final MediaType.Binary APPLICATION_X_COMPRESS = akka.http.scaladsl.model.MediaTypes.application$divx$minuscompress();
    public static final MediaType.Binary APPLICATION_X_COMPRESSED = akka.http.scaladsl.model.MediaTypes.application$divx$minuscompressed();
    public static final MediaType.Binary APPLICATION_X_DEBIAN_PACKAGE = akka.http.scaladsl.model.MediaTypes.application$divx$minusdebian$minuspackage();
    public static final MediaType.Binary APPLICATION_X_DVI = akka.http.scaladsl.model.MediaTypes.application$divx$minusdvi();
    public static final MediaType.Binary APPLICATION_X_FONT_TRUETYPE = akka.http.scaladsl.model.MediaTypes.application$divx$minusfont$minustruetype();
    public static final MediaType.Binary APPLICATION_X_FONT_OPENTYPE = akka.http.scaladsl.model.MediaTypes.application$divx$minusfont$minusopentype();
    public static final MediaType.Binary APPLICATION_X_GTAR = akka.http.scaladsl.model.MediaTypes.application$divx$minusgtar();
    public static final MediaType.Binary APPLICATION_X_GZIP = akka.http.scaladsl.model.MediaTypes.application$divx$minusgzip();
    public static final MediaType.WithOpenCharset APPLICATION_X_LATEX = akka.http.scaladsl.model.MediaTypes.application$divx$minuslatex();
    public static final MediaType.Binary APPLICATION_X_RAR_COMPRESSED = akka.http.scaladsl.model.MediaTypes.application$divx$minusrar$minuscompressed();
    public static final MediaType.Binary APPLICATION_X_REDHAT_PACKAGE_MANAGER = akka.http.scaladsl.model.MediaTypes.application$divx$minusredhat$minuspackage$minusmanager();
    public static final MediaType.Binary APPLICATION_X_SHOCKWAVE_FLASH = akka.http.scaladsl.model.MediaTypes.application$divx$minusshockwave$minusflash();
    public static final MediaType.Binary APPLICATION_X_TAR = akka.http.scaladsl.model.MediaTypes.application$divx$minustar();
    public static final MediaType.Binary APPLICATION_X_TEX = akka.http.scaladsl.model.MediaTypes.application$divx$minustex();
    public static final MediaType.Binary APPLICATION_X_TEXINFO = akka.http.scaladsl.model.MediaTypes.application$divx$minustexinfo();
    public static final MediaType.WithOpenCharset APPLICATION_X_VRML = akka.http.scaladsl.model.MediaTypes.application$divx$minusvrml();
    public static final MediaType.WithOpenCharset APPLICATION_X_WWW_FORM_URLENCODED = akka.http.scaladsl.model.MediaTypes.application$divx$minuswww$minusform$minusurlencoded();
    public static final MediaType.Binary APPLICATION_X_X509_CA_CERT = akka.http.scaladsl.model.MediaTypes.application$divx$minusx509$minusca$minuscert();
    public static final MediaType.Binary APPLICATION_X_XPINSTALL = akka.http.scaladsl.model.MediaTypes.application$divx$minusxpinstall();
    public static final MediaType.WithOpenCharset APPLICATION_XHTML_XML = akka.http.scaladsl.model.MediaTypes.application$divxhtml$plusxml();
    public static final MediaType.WithOpenCharset APPLICATION_XML_DTD = akka.http.scaladsl.model.MediaTypes.application$divxml$minusdtd();
    public static final MediaType.WithOpenCharset APPLICATION_XML = akka.http.scaladsl.model.MediaTypes.application$divxml();
    public static final MediaType.Binary APPLICATION_ZIP = akka.http.scaladsl.model.MediaTypes.application$divzip();

    public static final MediaType.Binary AUDIO_AIFF = akka.http.scaladsl.model.MediaTypes.audio$divaiff();
    public static final MediaType.Binary AUDIO_BASIC = akka.http.scaladsl.model.MediaTypes.audio$divbasic();
    public static final MediaType.Binary AUDIO_MIDI = akka.http.scaladsl.model.MediaTypes.audio$divmidi();
    public static final MediaType.Binary AUDIO_MOD = akka.http.scaladsl.model.MediaTypes.audio$divmod();
    public static final MediaType.Binary AUDIO_MPEG = akka.http.scaladsl.model.MediaTypes.audio$divmpeg();
    public static final MediaType.Binary AUDIO_OGG = akka.http.scaladsl.model.MediaTypes.audio$divogg();
    public static final MediaType.Binary AUDIO_VOC = akka.http.scaladsl.model.MediaTypes.audio$divvoc();
    public static final MediaType.Binary AUDIO_VORBIS = akka.http.scaladsl.model.MediaTypes.audio$divvorbis();
    public static final MediaType.Binary AUDIO_VOXWARE = akka.http.scaladsl.model.MediaTypes.audio$divvoxware();
    public static final MediaType.Binary AUDIO_WAV = akka.http.scaladsl.model.MediaTypes.audio$divwav();
    public static final MediaType.Binary AUDIO_X_REALAUDIO = akka.http.scaladsl.model.MediaTypes.audio$divx$minusrealaudio();
    public static final MediaType.Binary AUDIO_X_PSID = akka.http.scaladsl.model.MediaTypes.audio$divx$minuspsid();
    public static final MediaType.Binary AUDIO_XM = akka.http.scaladsl.model.MediaTypes.audio$divxm();
    public static final MediaType.Binary AUDIO_WEBM = akka.http.scaladsl.model.MediaTypes.audio$divwebm();

    public static final MediaType.Binary IMAGE_GIF = akka.http.scaladsl.model.MediaTypes.image$divgif();
    public static final MediaType.Binary IMAGE_JPEG = akka.http.scaladsl.model.MediaTypes.image$divjpeg();
    public static final MediaType.Binary IMAGE_PICT = akka.http.scaladsl.model.MediaTypes.image$divpict();
    public static final MediaType.Binary IMAGE_PNG = akka.http.scaladsl.model.MediaTypes.image$divpng();
    public static final MediaType.Binary IMAGE_SVG_XML = akka.http.scaladsl.model.MediaTypes.image$divsvg$plusxml();
    public static final MediaType.Binary IMAGE_TIFF = akka.http.scaladsl.model.MediaTypes.image$divtiff();
    public static final MediaType.Binary IMAGE_X_ICON = akka.http.scaladsl.model.MediaTypes.image$divx$minusicon();
    public static final MediaType.Binary IMAGE_X_MS_BMP = akka.http.scaladsl.model.MediaTypes.image$divx$minusms$minusbmp();
    public static final MediaType.Binary IMAGE_X_PCX = akka.http.scaladsl.model.MediaTypes.image$divx$minuspcx();
    public static final MediaType.Binary IMAGE_X_PICT = akka.http.scaladsl.model.MediaTypes.image$divx$minuspict();
    public static final MediaType.Binary IMAGE_X_QUICKTIME = akka.http.scaladsl.model.MediaTypes.image$divx$minusquicktime();
    public static final MediaType.Binary IMAGE_X_RGB = akka.http.scaladsl.model.MediaTypes.image$divx$minusrgb();
    public static final MediaType.Binary IMAGE_X_XBITMAP = akka.http.scaladsl.model.MediaTypes.image$divx$minusxbitmap();
    public static final MediaType.Binary IMAGE_X_XPIXMAP = akka.http.scaladsl.model.MediaTypes.image$divx$minusxpixmap();
    public static final MediaType.Binary IMAGE_WEBP = akka.http.scaladsl.model.MediaTypes.image$divwebp();

    public static final MediaType.Binary MESSAGE_HTTP = akka.http.scaladsl.model.MediaTypes.message$divhttp();
    public static final MediaType.Binary MESSAGE_DELIVERY_STATUS = akka.http.scaladsl.model.MediaTypes.message$divdelivery$minusstatus();
    public static final MediaType.Binary MESSAGE_RFC822 = akka.http.scaladsl.model.MediaTypes.message$divrfc822();

    public static final MediaType.WithOpenCharset MULTIPART_MIXED = akka.http.scaladsl.model.MediaTypes.multipart$divmixed();
    public static final MediaType.WithOpenCharset MULTIPART_ALTERNATIVE = akka.http.scaladsl.model.MediaTypes.multipart$divalternative();
    public static final MediaType.WithOpenCharset MULTIPART_RELATED = akka.http.scaladsl.model.MediaTypes.multipart$divrelated();
    public static final MediaType.WithOpenCharset MULTIPART_FORM_DATA = akka.http.scaladsl.model.MediaTypes.multipart$divform$minusdata();
    public static final MediaType.WithOpenCharset MULTIPART_SIGNED = akka.http.scaladsl.model.MediaTypes.multipart$divsigned();
    public static final MediaType.WithOpenCharset MULTIPART_ENCRYPTED = akka.http.scaladsl.model.MediaTypes.multipart$divencrypted();
    public static final MediaType.WithOpenCharset MULTIPART_BYTERANGES = akka.http.scaladsl.model.MediaTypes.multipart$divbyteranges();

    public static final MediaType.WithOpenCharset TEXT_ASP = akka.http.scaladsl.model.MediaTypes.text$divasp();
    public static final MediaType.WithOpenCharset TEXT_CACHE_MANIFEST = akka.http.scaladsl.model.MediaTypes.text$divcache$minusmanifest();
    public static final MediaType.WithOpenCharset TEXT_CALENDAR = akka.http.scaladsl.model.MediaTypes.text$divcalendar();
    public static final MediaType.WithOpenCharset TEXT_CSS = akka.http.scaladsl.model.MediaTypes.text$divcss();
    public static final MediaType.WithOpenCharset TEXT_CSV = akka.http.scaladsl.model.MediaTypes.text$divcsv();
    public static final MediaType.WithOpenCharset TEXT_HTML = akka.http.scaladsl.model.MediaTypes.text$divhtml();
    public static final MediaType.WithOpenCharset TEXT_MARKDOWN = akka.http.scaladsl.model.MediaTypes.text$divmarkdown();
    public static final MediaType.WithOpenCharset TEXT_MCF = akka.http.scaladsl.model.MediaTypes.text$divmcf();
    public static final MediaType.WithOpenCharset TEXT_PLAIN = akka.http.scaladsl.model.MediaTypes.text$divplain();
    public static final MediaType.WithOpenCharset TEXT_RICHTEXT = akka.http.scaladsl.model.MediaTypes.text$divrichtext();
    public static final MediaType.WithOpenCharset TEXT_TAB_SEPARATED_VALUES = akka.http.scaladsl.model.MediaTypes.text$divtab$minusseparated$minusvalues();
    public static final MediaType.WithOpenCharset TEXT_URI_LIST = akka.http.scaladsl.model.MediaTypes.text$divuri$minuslist();
    public static final MediaType.WithOpenCharset TEXT_VND_WAP_WML = akka.http.scaladsl.model.MediaTypes.text$divvnd$u002Ewap$u002Ewml();
    public static final MediaType.WithOpenCharset TEXT_VND_WAP_WMLSCRIPT = akka.http.scaladsl.model.MediaTypes.text$divvnd$u002Ewap$u002Ewmlscript();
    public static final MediaType.WithOpenCharset TEXT_X_ASM = akka.http.scaladsl.model.MediaTypes.text$divx$minusasm();
    public static final MediaType.WithOpenCharset TEXT_X_C = akka.http.scaladsl.model.MediaTypes.text$divx$minusc();
    public static final MediaType.WithOpenCharset TEXT_X_COMPONENT = akka.http.scaladsl.model.MediaTypes.text$divx$minuscomponent();
    public static final MediaType.WithOpenCharset TEXT_X_H = akka.http.scaladsl.model.MediaTypes.text$divx$minush();
    public static final MediaType.WithOpenCharset TEXT_X_JAVA_SOURCE = akka.http.scaladsl.model.MediaTypes.text$divx$minusjava$minussource();
    public static final MediaType.WithOpenCharset TEXT_X_PASCAL = akka.http.scaladsl.model.MediaTypes.text$divx$minuspascal();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPT = akka.http.scaladsl.model.MediaTypes.text$divx$minusscript();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTCSH = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptcsh();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTELISP = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptelisp();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTKSH = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptksh();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTLISP = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptlisp();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTPERL = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptperl();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTPERL_MODULE = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptperl$minusmodule();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTPHYTON = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptphyton();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTREXX = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptrexx();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTSCHEME = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptscheme();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTSH = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptsh();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTTCL = akka.http.scaladsl.model.MediaTypes.text$divx$minusscripttcl();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTTCSH = akka.http.scaladsl.model.MediaTypes.text$divx$minusscripttcsh();
    public static final MediaType.WithOpenCharset TEXT_X_SCRIPTZSH = akka.http.scaladsl.model.MediaTypes.text$divx$minusscriptzsh();
    public static final MediaType.WithOpenCharset TEXT_X_SERVER_PARSED_HTML = akka.http.scaladsl.model.MediaTypes.text$divx$minusserver$minusparsed$minushtml();
    public static final MediaType.WithOpenCharset TEXT_X_SETEXT = akka.http.scaladsl.model.MediaTypes.text$divx$minussetext();
    public static final MediaType.WithOpenCharset TEXT_X_SGML = akka.http.scaladsl.model.MediaTypes.text$divx$minussgml();
    public static final MediaType.WithOpenCharset TEXT_X_SPEECH = akka.http.scaladsl.model.MediaTypes.text$divx$minusspeech();
    public static final MediaType.WithOpenCharset TEXT_X_UUENCODE = akka.http.scaladsl.model.MediaTypes.text$divx$minusuuencode();
    public static final MediaType.WithOpenCharset TEXT_X_VCALENDAR = akka.http.scaladsl.model.MediaTypes.text$divx$minusvcalendar();
    public static final MediaType.WithOpenCharset TEXT_X_VCARD = akka.http.scaladsl.model.MediaTypes.text$divx$minusvcard();
    public static final MediaType.WithOpenCharset TEXT_XML = akka.http.scaladsl.model.MediaTypes.text$divxml();

    public static final MediaType.Binary VIDEO_AVS_VIDEO = akka.http.scaladsl.model.MediaTypes.video$divavs$minusvideo();
    public static final MediaType.Binary VIDEO_DIVX = akka.http.scaladsl.model.MediaTypes.video$divdivx();
    public static final MediaType.Binary VIDEO_GL = akka.http.scaladsl.model.MediaTypes.video$divgl();
    public static final MediaType.Binary VIDEO_MP4 = akka.http.scaladsl.model.MediaTypes.video$divmp4();
    public static final MediaType.Binary VIDEO_MPEG = akka.http.scaladsl.model.MediaTypes.video$divmpeg();
    public static final MediaType.Binary VIDEO_OGG = akka.http.scaladsl.model.MediaTypes.video$divogg();
    public static final MediaType.Binary VIDEO_QUICKTIME = akka.http.scaladsl.model.MediaTypes.video$divquicktime();
    public static final MediaType.Binary VIDEO_X_DV = akka.http.scaladsl.model.MediaTypes.video$divx$minusdv();
    public static final MediaType.Binary VIDEO_X_FLV = akka.http.scaladsl.model.MediaTypes.video$divx$minusflv();
    public static final MediaType.Binary VIDEO_X_MOTION_JPEG = akka.http.scaladsl.model.MediaTypes.video$divx$minusmotion$minusjpeg();
    public static final MediaType.Binary VIDEO_X_MS_ASF = akka.http.scaladsl.model.MediaTypes.video$divx$minusms$minusasf();
    public static final MediaType.Binary VIDEO_X_MSVIDEO = akka.http.scaladsl.model.MediaTypes.video$divx$minusmsvideo();
    public static final MediaType.Binary VIDEO_X_SGI_MOVIE = akka.http.scaladsl.model.MediaTypes.video$divx$minussgi$minusmovie();
    public static final MediaType.Binary VIDEO_WEBM = akka.http.scaladsl.model.MediaTypes.video$divwebm();

    public static MediaType.Binary applicationBinary(String subType, boolean compressible, String... fileExtensions) {
        akka.http.scaladsl.model.MediaType.Compressibility comp = compressible ?
                akka.http.scaladsl.model.MediaType.Compressible$.MODULE$ : akka.http.scaladsl.model.MediaType.NotCompressible$.MODULE$;

        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        return akka.http.scaladsl.model.MediaType.applicationBinary(subType, comp, fileEx);
    }

    public static MediaType.WithFixedCharset applicationWithFixedCharset(String subType, HttpCharset charset, String... fileExtensions) {
        akka.http.scaladsl.model.HttpCharset cs = (akka.http.scaladsl.model.HttpCharset) charset;

        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        return akka.http.scaladsl.model.MediaType.applicationWithFixedCharset(subType, cs, fileEx);
    }

    public static MediaType.WithOpenCharset applicationWithOpenCharset(String subType, String... fileExtensions) {
        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        return akka.http.scaladsl.model.MediaType.applicationWithOpenCharset(subType, fileEx);
    }

    public static MediaType.Binary audio(String subType, boolean compressible, String... fileExtensions) {
        akka.http.scaladsl.model.MediaType.Compressibility comp = compressible ?
                akka.http.scaladsl.model.MediaType.Compressible$.MODULE$ : akka.http.scaladsl.model.MediaType.NotCompressible$.MODULE$;

        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        return akka.http.scaladsl.model.MediaType.audio(subType, comp, fileEx);
    }

    public static MediaType.Binary image(String subType, boolean compressible, String... fileExtensions) {
        akka.http.scaladsl.model.MediaType.Compressibility comp = compressible ?
                akka.http.scaladsl.model.MediaType.Compressible$.MODULE$ : akka.http.scaladsl.model.MediaType.NotCompressible$.MODULE$;

        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        return akka.http.scaladsl.model.MediaType.image(subType, comp, fileEx);
    }

    public static MediaType.Binary message(String subType, boolean compressible, String... fileExtensions) {
        akka.http.scaladsl.model.MediaType.Compressibility comp = compressible ?
                akka.http.scaladsl.model.MediaType.Compressible$.MODULE$ : akka.http.scaladsl.model.MediaType.NotCompressible$.MODULE$;

        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        return akka.http.scaladsl.model.MediaType.message(subType, comp, fileEx);
    }

    public static MediaType.WithOpenCharset text(String subType, String... fileExtensions) {
        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        return akka.http.scaladsl.model.MediaType.text(subType, fileEx);
    }

    public static MediaType.Binary video(String subType, boolean compressible, String... fileExtensions) {
        akka.http.scaladsl.model.MediaType.Compressibility comp = compressible ?
                akka.http.scaladsl.model.MediaType.Compressible$.MODULE$ : akka.http.scaladsl.model.MediaType.NotCompressible$.MODULE$;

        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        return akka.http.scaladsl.model.MediaType.video(subType, comp, fileEx);
    }

    // arguments have been reordered due to varargs having to be the last argument
    // should we create multiple overloads of this function?
    public static MediaType.Binary customBinary(String mainType, String subType, boolean compressible, java.util.Map<String, String> params, boolean allowArbitrarySubtypes, String... fileExtensions) {
        akka.http.scaladsl.model.MediaType.Compressibility comp = compressible ?
                akka.http.scaladsl.model.MediaType.Compressible$.MODULE$ : akka.http.scaladsl.model.MediaType.NotCompressible$.MODULE$;

        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        scala.collection.immutable.Map<String, String> p = Util.convertMapToScala(params);

        return akka.http.scaladsl.model.MediaType.customBinary(mainType, subType, comp, fileEx, p, allowArbitrarySubtypes);
    }

    public static MediaType.WithFixedCharset customWithFixedCharset(String mainType, String subType, HttpCharset charset, java.util.Map<String, String> params, boolean allowArbitrarySubtypes, String... fileExtensions) {
        akka.http.scaladsl.model.HttpCharset cs = (akka.http.scaladsl.model.HttpCharset) charset;

        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        scala.collection.immutable.Map<String, String> p = Util.convertMapToScala(params);

        return akka.http.scaladsl.model.MediaType.customWithFixedCharset(mainType, subType, cs, fileEx, p, allowArbitrarySubtypes);
    }

    public static MediaType.WithOpenCharset customWithOpenCharset(String mainType, String subType, java.util.Map<String, String> params, boolean allowArbitrarySubtypes, String... fileExtensions) {
        scala.collection.immutable.List<String> fileEx =
                scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(fileExtensions)).toList();

        scala.collection.immutable.Map<String, String> p = Util.convertMapToScala(params);

        return akka.http.scaladsl.model.MediaType.customWithOpenCharset(mainType, subType, fileEx, p, allowArbitrarySubtypes);
    }

    public static MediaType.Multipart customMultipart(String subType, java.util.Map<String, String> params) {
        scala.collection.immutable.Map<String, String> p = Util.convertMapToScala(params);
        return akka.http.scaladsl.model.MediaType.customMultipart(subType, p);
    }


    /**
     * Creates a custom media type.
     */
    public static MediaType custom(String value, boolean binary, boolean compressible) {
        akka.http.scaladsl.model.MediaType.Compressibility comp = compressible ?
                akka.http.scaladsl.model.MediaType.Compressible$.MODULE$ : akka.http.scaladsl.model.MediaType.NotCompressible$.MODULE$;
        return akka.http.scaladsl.model.MediaType.custom(value, binary, comp , 
            akka.http.scaladsl.model.MediaType.custom$default$4());
    }

    /**
     * Looks up a media-type with the given main-type and sub-type.
     */
    public static Optional<MediaType> lookup(String mainType, String subType) {
        return Util.<scala.Tuple2<String, String>, MediaType, akka.http.scaladsl.model.MediaType>lookupInRegistry(MediaTypes$.MODULE$, new scala.Tuple2<String, String>(mainType, subType));
    }
}
