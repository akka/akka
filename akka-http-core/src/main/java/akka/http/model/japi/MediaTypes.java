/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.http.model.MediaTypes$;
import akka.japi.Option;

import java.util.Map;

/**
 * Contains the set of predefined media-types.
 */
public abstract class MediaTypes {
    public static final MediaType APPLICATION_ATOM_XML = akka.http.model.MediaTypes.application$divatom$plusxml();
    public static final MediaType APPLICATION_BASE64 = akka.http.model.MediaTypes.application$divbase64();
    public static final MediaType APPLICATION_EXCEL = akka.http.model.MediaTypes.application$divexcel();
    public static final MediaType APPLICATION_FONT_WOFF = akka.http.model.MediaTypes.application$divfont$minuswoff();
    public static final MediaType APPLICATION_GNUTAR = akka.http.model.MediaTypes.application$divgnutar();
    public static final MediaType APPLICATION_JAVA_ARCHIVE = akka.http.model.MediaTypes.application$divjava$minusarchive();
    public static final MediaType APPLICATION_JAVASCRIPT = akka.http.model.MediaTypes.application$divjavascript();
    public static final MediaType APPLICATION_JSON = akka.http.model.MediaTypes.application$divjson();
    public static final MediaType APPLICATION_JSON_PATCH_JSON = akka.http.model.MediaTypes.application$divjson$minuspatch$plusjson();
    public static final MediaType APPLICATION_LHA = akka.http.model.MediaTypes.application$divlha();
    public static final MediaType APPLICATION_LZX = akka.http.model.MediaTypes.application$divlzx();
    public static final MediaType APPLICATION_MSPOWERPOINT = akka.http.model.MediaTypes.application$divmspowerpoint();
    public static final MediaType APPLICATION_MSWORD = akka.http.model.MediaTypes.application$divmsword();
    public static final MediaType APPLICATION_OCTET_STREAM = akka.http.model.MediaTypes.application$divoctet$minusstream();
    public static final MediaType APPLICATION_PDF = akka.http.model.MediaTypes.application$divpdf();
    public static final MediaType APPLICATION_POSTSCRIPT = akka.http.model.MediaTypes.application$divpostscript();
    public static final MediaType APPLICATION_RSS_XML = akka.http.model.MediaTypes.application$divrss$plusxml();
    public static final MediaType APPLICATION_SOAP_XML = akka.http.model.MediaTypes.application$divsoap$plusxml();
    public static final MediaType APPLICATION_VND_API_JSON = akka.http.model.MediaTypes.application$divvnd$u002Eapi$plusjson();
    public static final MediaType APPLICATION_VND_GOOGLE_EARTH_KML_XML = akka.http.model.MediaTypes.application$divvnd$u002Egoogle$minusearth$u002Ekml$plusxml();
    public static final MediaType APPLICATION_VND_GOOGLE_EARTH_KMZ = akka.http.model.MediaTypes.application$divvnd$u002Egoogle$minusearth$u002Ekmz();
    public static final MediaType APPLICATION_VND_MS_FONTOBJECT = akka.http.model.MediaTypes.application$divvnd$u002Ems$minusfontobject();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_CHART = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Echart();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_DATABASE = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Edatabase();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_FORMULA = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Eformula();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_GRAPHICS = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Egraphics();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_IMAGE = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Eimage();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Epresentation();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Espreadsheet();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Etext();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT_MASTER = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Etext$minusmaster();
    public static final MediaType APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT_WEB = akka.http.model.MediaTypes.application$divvnd$u002Eoasis$u002Eopendocument$u002Etext$minusweb();
    public static final MediaType APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_PRESENTATION = akka.http.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Epresentationml$u002Epresentation();
    public static final MediaType APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_SLIDE = akka.http.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Epresentationml$u002Eslide();
    public static final MediaType APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_SLIDESHOW = akka.http.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Epresentationml$u002Eslideshow();
    public static final MediaType APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_PRESENTATIONML_TEMPLATE = akka.http.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Epresentationml$u002Etemplate();
    public static final MediaType APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_SPREADSHEETML_SHEET = akka.http.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Espreadsheetml$u002Esheet();
    public static final MediaType APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_SPREADSHEETML_TEMPLATE = akka.http.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Espreadsheetml$u002Etemplate();
    public static final MediaType APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_WORDPROCESSINGML_DOCUMENT = akka.http.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Ewordprocessingml$u002Edocument();
    public static final MediaType APPLICATION_VND_OPENXMLFORMATS_OFFICEDOCUMENT_WORDPROCESSINGML_TEMPLATE = akka.http.model.MediaTypes.application$divvnd$u002Eopenxmlformats$minusofficedocument$u002Ewordprocessingml$u002Etemplate();
    public static final MediaType APPLICATION_X_7Z_COMPRESSED = akka.http.model.MediaTypes.application$divx$minus7z$minuscompressed();
    public static final MediaType APPLICATION_X_ACE_COMPRESSED = akka.http.model.MediaTypes.application$divx$minusace$minuscompressed();
    public static final MediaType APPLICATION_X_APPLE_DISKIMAGE = akka.http.model.MediaTypes.application$divx$minusapple$minusdiskimage();
    public static final MediaType APPLICATION_X_ARC_COMPRESSED = akka.http.model.MediaTypes.application$divx$minusarc$minuscompressed();
    public static final MediaType APPLICATION_X_BZIP = akka.http.model.MediaTypes.application$divx$minusbzip();
    public static final MediaType APPLICATION_X_BZIP2 = akka.http.model.MediaTypes.application$divx$minusbzip2();
    public static final MediaType APPLICATION_X_CHROME_EXTENSION = akka.http.model.MediaTypes.application$divx$minuschrome$minusextension();
    public static final MediaType APPLICATION_X_COMPRESS = akka.http.model.MediaTypes.application$divx$minuscompress();
    public static final MediaType APPLICATION_X_COMPRESSED = akka.http.model.MediaTypes.application$divx$minuscompressed();
    public static final MediaType APPLICATION_X_DEBIAN_PACKAGE = akka.http.model.MediaTypes.application$divx$minusdebian$minuspackage();
    public static final MediaType APPLICATION_X_DVI = akka.http.model.MediaTypes.application$divx$minusdvi();
    public static final MediaType APPLICATION_X_FONT_TRUETYPE = akka.http.model.MediaTypes.application$divx$minusfont$minustruetype();
    public static final MediaType APPLICATION_X_FONT_OPENTYPE = akka.http.model.MediaTypes.application$divx$minusfont$minusopentype();
    public static final MediaType APPLICATION_X_GTAR = akka.http.model.MediaTypes.application$divx$minusgtar();
    public static final MediaType APPLICATION_X_GZIP = akka.http.model.MediaTypes.application$divx$minusgzip();
    public static final MediaType APPLICATION_X_LATEX = akka.http.model.MediaTypes.application$divx$minuslatex();
    public static final MediaType APPLICATION_X_RAR_COMPRESSED = akka.http.model.MediaTypes.application$divx$minusrar$minuscompressed();
    public static final MediaType APPLICATION_X_REDHAT_PACKAGE_MANAGER = akka.http.model.MediaTypes.application$divx$minusredhat$minuspackage$minusmanager();
    public static final MediaType APPLICATION_X_SHOCKWAVE_FLASH = akka.http.model.MediaTypes.application$divx$minusshockwave$minusflash();
    public static final MediaType APPLICATION_X_TAR = akka.http.model.MediaTypes.application$divx$minustar();
    public static final MediaType APPLICATION_X_TEX = akka.http.model.MediaTypes.application$divx$minustex();
    public static final MediaType APPLICATION_X_TEXINFO = akka.http.model.MediaTypes.application$divx$minustexinfo();
    public static final MediaType APPLICATION_X_VRML = akka.http.model.MediaTypes.application$divx$minusvrml();
    public static final MediaType APPLICATION_X_WWW_FORM_URLENCODED = akka.http.model.MediaTypes.application$divx$minuswww$minusform$minusurlencoded();
    public static final MediaType APPLICATION_X_X509_CA_CERT = akka.http.model.MediaTypes.application$divx$minusx509$minusca$minuscert();
    public static final MediaType APPLICATION_X_XPINSTALL = akka.http.model.MediaTypes.application$divx$minusxpinstall();
    public static final MediaType APPLICATION_XHTML_XML = akka.http.model.MediaTypes.application$divxhtml$plusxml();
    public static final MediaType APPLICATION_XML_DTD = akka.http.model.MediaTypes.application$divxml$minusdtd();
    public static final MediaType APPLICATION_XML = akka.http.model.MediaTypes.application$divxml();
    public static final MediaType APPLICATION_ZIP = akka.http.model.MediaTypes.application$divzip();
    public static final MediaType AUDIO_AIFF = akka.http.model.MediaTypes.audio$divaiff();
    public static final MediaType AUDIO_BASIC = akka.http.model.MediaTypes.audio$divbasic();
    public static final MediaType AUDIO_MIDI = akka.http.model.MediaTypes.audio$divmidi();
    public static final MediaType AUDIO_MOD = akka.http.model.MediaTypes.audio$divmod();
    public static final MediaType AUDIO_MPEG = akka.http.model.MediaTypes.audio$divmpeg();
    public static final MediaType AUDIO_OGG = akka.http.model.MediaTypes.audio$divogg();
    public static final MediaType AUDIO_VOC = akka.http.model.MediaTypes.audio$divvoc();
    public static final MediaType AUDIO_VORBIS = akka.http.model.MediaTypes.audio$divvorbis();
    public static final MediaType AUDIO_VOXWARE = akka.http.model.MediaTypes.audio$divvoxware();
    public static final MediaType AUDIO_WAV = akka.http.model.MediaTypes.audio$divwav();
    public static final MediaType AUDIO_X_REALAUDIO = akka.http.model.MediaTypes.audio$divx$minusrealaudio();
    public static final MediaType AUDIO_X_PSID = akka.http.model.MediaTypes.audio$divx$minuspsid();
    public static final MediaType AUDIO_XM = akka.http.model.MediaTypes.audio$divxm();
    public static final MediaType AUDIO_WEBM = akka.http.model.MediaTypes.audio$divwebm();
    public static final MediaType IMAGE_GIF = akka.http.model.MediaTypes.image$divgif();
    public static final MediaType IMAGE_JPEG = akka.http.model.MediaTypes.image$divjpeg();
    public static final MediaType IMAGE_PICT = akka.http.model.MediaTypes.image$divpict();
    public static final MediaType IMAGE_PNG = akka.http.model.MediaTypes.image$divpng();
    public static final MediaType IMAGE_SVG_XML = akka.http.model.MediaTypes.image$divsvg$plusxml();
    public static final MediaType IMAGE_TIFF = akka.http.model.MediaTypes.image$divtiff();
    public static final MediaType IMAGE_X_ICON = akka.http.model.MediaTypes.image$divx$minusicon();
    public static final MediaType IMAGE_X_MS_BMP = akka.http.model.MediaTypes.image$divx$minusms$minusbmp();
    public static final MediaType IMAGE_X_PCX = akka.http.model.MediaTypes.image$divx$minuspcx();
    public static final MediaType IMAGE_X_PICT = akka.http.model.MediaTypes.image$divx$minuspict();
    public static final MediaType IMAGE_X_QUICKTIME = akka.http.model.MediaTypes.image$divx$minusquicktime();
    public static final MediaType IMAGE_X_RGB = akka.http.model.MediaTypes.image$divx$minusrgb();
    public static final MediaType IMAGE_X_XBITMAP = akka.http.model.MediaTypes.image$divx$minusxbitmap();
    public static final MediaType IMAGE_X_XPIXMAP = akka.http.model.MediaTypes.image$divx$minusxpixmap();
    public static final MediaType IMAGE_WEBP = akka.http.model.MediaTypes.image$divwebp();
    public static final MediaType MESSAGE_HTTP = akka.http.model.MediaTypes.message$divhttp();
    public static final MediaType MESSAGE_DELIVERY_STATUS = akka.http.model.MediaTypes.message$divdelivery$minusstatus();
    public static final MediaType MESSAGE_RFC822 = akka.http.model.MediaTypes.message$divrfc822();
    public static final MediaType MULTIPART_MIXED = akka.http.model.MediaTypes.multipart$divmixed();
    public static final MediaType MULTIPART_ALTERNATIVE = akka.http.model.MediaTypes.multipart$divalternative();
    public static final MediaType MULTIPART_RELATED = akka.http.model.MediaTypes.multipart$divrelated();
    public static final MediaType MULTIPART_FORM_DATA = akka.http.model.MediaTypes.multipart$divform$minusdata();
    public static final MediaType MULTIPART_SIGNED = akka.http.model.MediaTypes.multipart$divsigned();
    public static final MediaType MULTIPART_ENCRYPTED = akka.http.model.MediaTypes.multipart$divencrypted();
    public static final MediaType MULTIPART_BYTERANGES = akka.http.model.MediaTypes.multipart$divbyteranges();
    public static final MediaType TEXT_ASP = akka.http.model.MediaTypes.text$divasp();
    public static final MediaType TEXT_CACHE_MANIFEST = akka.http.model.MediaTypes.text$divcache$minusmanifest();
    public static final MediaType TEXT_CALENDAR = akka.http.model.MediaTypes.text$divcalendar();
    public static final MediaType TEXT_CSS = akka.http.model.MediaTypes.text$divcss();
    public static final MediaType TEXT_CSV = akka.http.model.MediaTypes.text$divcsv();
    public static final MediaType TEXT_HTML = akka.http.model.MediaTypes.text$divhtml();
    public static final MediaType TEXT_MCF = akka.http.model.MediaTypes.text$divmcf();
    public static final MediaType TEXT_PLAIN = akka.http.model.MediaTypes.text$divplain();
    public static final MediaType TEXT_RICHTEXT = akka.http.model.MediaTypes.text$divrichtext();
    public static final MediaType TEXT_TAB_SEPARATED_VALUES = akka.http.model.MediaTypes.text$divtab$minusseparated$minusvalues();
    public static final MediaType TEXT_URI_LIST = akka.http.model.MediaTypes.text$divuri$minuslist();
    public static final MediaType TEXT_VND_WAP_WML = akka.http.model.MediaTypes.text$divvnd$u002Ewap$u002Ewml();
    public static final MediaType TEXT_VND_WAP_WMLSCRIPT = akka.http.model.MediaTypes.text$divvnd$u002Ewap$u002Ewmlscript();
    public static final MediaType TEXT_X_ASM = akka.http.model.MediaTypes.text$divx$minusasm();
    public static final MediaType TEXT_X_C = akka.http.model.MediaTypes.text$divx$minusc();
    public static final MediaType TEXT_X_COMPONENT = akka.http.model.MediaTypes.text$divx$minuscomponent();
    public static final MediaType TEXT_X_H = akka.http.model.MediaTypes.text$divx$minush();
    public static final MediaType TEXT_X_JAVA_SOURCE = akka.http.model.MediaTypes.text$divx$minusjava$minussource();
    public static final MediaType TEXT_X_PASCAL = akka.http.model.MediaTypes.text$divx$minuspascal();
    public static final MediaType TEXT_X_SCRIPT = akka.http.model.MediaTypes.text$divx$minusscript();
    public static final MediaType TEXT_X_SCRIPTCSH = akka.http.model.MediaTypes.text$divx$minusscriptcsh();
    public static final MediaType TEXT_X_SCRIPTELISP = akka.http.model.MediaTypes.text$divx$minusscriptelisp();
    public static final MediaType TEXT_X_SCRIPTKSH = akka.http.model.MediaTypes.text$divx$minusscriptksh();
    public static final MediaType TEXT_X_SCRIPTLISP = akka.http.model.MediaTypes.text$divx$minusscriptlisp();
    public static final MediaType TEXT_X_SCRIPTPERL = akka.http.model.MediaTypes.text$divx$minusscriptperl();
    public static final MediaType TEXT_X_SCRIPTPERL_MODULE = akka.http.model.MediaTypes.text$divx$minusscriptperl$minusmodule();
    public static final MediaType TEXT_X_SCRIPTPHYTON = akka.http.model.MediaTypes.text$divx$minusscriptphyton();
    public static final MediaType TEXT_X_SCRIPTREXX = akka.http.model.MediaTypes.text$divx$minusscriptrexx();
    public static final MediaType TEXT_X_SCRIPTSCHEME = akka.http.model.MediaTypes.text$divx$minusscriptscheme();
    public static final MediaType TEXT_X_SCRIPTSH = akka.http.model.MediaTypes.text$divx$minusscriptsh();
    public static final MediaType TEXT_X_SCRIPTTCL = akka.http.model.MediaTypes.text$divx$minusscripttcl();
    public static final MediaType TEXT_X_SCRIPTTCSH = akka.http.model.MediaTypes.text$divx$minusscripttcsh();
    public static final MediaType TEXT_X_SCRIPTZSH = akka.http.model.MediaTypes.text$divx$minusscriptzsh();
    public static final MediaType TEXT_X_SERVER_PARSED_HTML = akka.http.model.MediaTypes.text$divx$minusserver$minusparsed$minushtml();
    public static final MediaType TEXT_X_SETEXT = akka.http.model.MediaTypes.text$divx$minussetext();
    public static final MediaType TEXT_X_SGML = akka.http.model.MediaTypes.text$divx$minussgml();
    public static final MediaType TEXT_X_SPEECH = akka.http.model.MediaTypes.text$divx$minusspeech();
    public static final MediaType TEXT_X_UUENCODE = akka.http.model.MediaTypes.text$divx$minusuuencode();
    public static final MediaType TEXT_X_VCALENDAR = akka.http.model.MediaTypes.text$divx$minusvcalendar();
    public static final MediaType TEXT_X_VCARD = akka.http.model.MediaTypes.text$divx$minusvcard();
    public static final MediaType TEXT_XML = akka.http.model.MediaTypes.text$divxml();
    public static final MediaType VIDEO_AVS_VIDEO = akka.http.model.MediaTypes.video$divavs$minusvideo();
    public static final MediaType VIDEO_DIVX = akka.http.model.MediaTypes.video$divdivx();
    public static final MediaType VIDEO_GL = akka.http.model.MediaTypes.video$divgl();
    public static final MediaType VIDEO_MP4 = akka.http.model.MediaTypes.video$divmp4();
    public static final MediaType VIDEO_MPEG = akka.http.model.MediaTypes.video$divmpeg();
    public static final MediaType VIDEO_OGG = akka.http.model.MediaTypes.video$divogg();
    public static final MediaType VIDEO_QUICKTIME = akka.http.model.MediaTypes.video$divquicktime();
    public static final MediaType VIDEO_X_DV = akka.http.model.MediaTypes.video$divx$minusdv();
    public static final MediaType VIDEO_X_FLV = akka.http.model.MediaTypes.video$divx$minusflv();
    public static final MediaType VIDEO_X_MOTION_JPEG = akka.http.model.MediaTypes.video$divx$minusmotion$minusjpeg();
    public static final MediaType VIDEO_X_MS_ASF = akka.http.model.MediaTypes.video$divx$minusms$minusasf();
    public static final MediaType VIDEO_X_MSVIDEO = akka.http.model.MediaTypes.video$divx$minusmsvideo();
    public static final MediaType VIDEO_X_SGI_MOVIE = akka.http.model.MediaTypes.video$divx$minussgi$minusmovie();
    public static final MediaType VIDEO_WEBM = akka.http.model.MediaTypes.video$divwebm();

    /**
     * Register a custom media type.
     */
    public static MediaType registerCustom(
            String mainType,
            String subType,
            boolean compressible,
            boolean binary,
            Iterable<String> fileExtensions,
            Map<String, String> params) {
        return akka.http.model.MediaTypes.register(akka.http.model.MediaType.custom(mainType, subType, compressible, binary, Util.<String, String>convertIterable(fileExtensions), Util.convertMapToScala(params), false));
    }

    /**
     * Looks up a media-type with the given main-type and sub-type.
     */
    public static Option<MediaType> lookup(String mainType, String subType) {
        return Util.lookupInRegistry(MediaTypes$.MODULE$, new scala.Tuple2<String, String>(mainType, subType));
    }
}
