/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pki.pem

import java.util.Base64

/**
  * Decodes lax PEM encoded data, according to
  *
  * https://tools.ietf.org/html/rfc7468
  */
object PEMDecoder {

  // I believe this regex matches the RFC7468 Lax ABNF semantics  jkhdft exactly.
  private val PEMRegex = {
    // Luckily, Java Pattern's \s matches the RFCs W ABNF expression perfectly
    // (space, tab, carriage return, line feed, form feed, vertical tab)

    // The variables here are named to match the expressions in the RFC7468 ABNF
    // description. The content of the regex may not match the structure of the
    // expression because sometimes there are nicer way to do things in regexes.

    // All printable ASCII characters minus hyphen
    val labelchar = """[\p{Print}&&[^-]]"""
    // Starts and finishes with a labelchar, with as many label chars and hyphens or
    // spaces in between, but no double spaces or hyphens, also may be empty.
    val label = raw"""(?:$labelchar(?:[\- ]?$labelchar)*)?"""
    // capturing group so we can extract the label
    val preeb = raw"""-----BEGIN ($label)-----"""
    // we don't extract the end label because the RFC says we can ignore it (it
    // doesn't have to match the begin label)
    val posteb = raw"""-----END $label-----"""
    // Any of the base64 chars (alphanum, +, /) and whitespace, followed by at most 2
    // padding characters, separated by zero to many whitespace characters
    val laxbase64text = """[A-Za-z0-9\+/\s]*(?:=\s*){0,2}"""

    val laxtextualmessage = raw"""\s*$preeb($laxbase64text)$posteb\s*"""

    laxtextualmessage.r
  }

  def decode(pemData: String): Either[String, PEMData] = {
    pemData match {
      case PEMRegex(label, base64) =>
        try {
          Right(PEMData(label, Base64.getMimeDecoder.decode(base64)))
        } catch {
          case iae: IllegalArgumentException =>
            Left(s"Error decoding base64 data from PEM data: ${iae.getMessage}")
        }

      case _ => Left("Not a PEM encoded data")
    }
  }

  case class PEMData(label: String, bytes: Array[Byte])

}
