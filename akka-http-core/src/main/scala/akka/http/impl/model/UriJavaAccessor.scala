package akka.http.impl.model

import akka.annotation.InternalApi
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Host
import java.nio.charset.Charset

/**
 * INTERNAL API.
 */
@InternalApi
private[http] abstract class UriJavaAccessor
/**
 * INTERNAL API.
 */
@InternalApi
private[http] object UriJavaAccessor {
  import collection.JavaConverters._

  def hostApply(string: String): Host = Uri.Host(string)
  def hostApply(string: String, mode: Uri.ParsingMode): Host = Uri.Host(string, mode = mode)
  def hostApply(string: String, charset: Charset): Host = Uri.Host(string, charset = charset)
  def emptyHost: Uri.Host = Uri.Host.Empty

  def queryApply(params: Array[akka.japi.Pair[String, String]]): Uri.Query = Uri.Query(params.map(_.toScala): _*)
  def queryApply(params: java.util.Map[String, String]): Uri.Query = Uri.Query(params.asScala.toSeq: _*)
  def queryApply(string: String, mode: Uri.ParsingMode): Uri.Query = Uri.Query(string, mode = mode)
  def queryApply(string: String, charset: Charset): Uri.Query = Uri.Query(string, charset = charset)
  def emptyQuery: Uri.Query = Uri.Query.Empty

  def pmStrict: Uri.ParsingMode = Uri.ParsingMode.Strict
  def pmRelaxed: Uri.ParsingMode = Uri.ParsingMode.Relaxed
}
