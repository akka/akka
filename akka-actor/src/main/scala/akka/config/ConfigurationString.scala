/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 *
 * Based on Configgy by Robey Pointer.
 *   Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package akka.config

import scala.util.matching.Regex


final class ConfigurationString(wrapped: String) {
  /**
   * For every section of a string that matches a regular expression, call
   * a function to determine a replacement (as in python's
   * `re.sub`). The function will be passed the Matcher object
   * corresponding to the substring that matches the pattern, and that
   * substring will be replaced by the function's result.
   *
   * For example, this call:
   *
   *     "ohio".regexSub("""h.""".r) { m => "n" }
   *
   * will return the string `"ono"`.
   *
   * The matches are found using `Matcher.find()` and so
   * will obey all the normal java rules (the matches will not overlap,
   * etc).
   *
   * @param re the regex pattern to replace
   * @param replace a function that takes Regex.MatchData objects and
   *     returns a string to substitute
   * @return the resulting string with replacements made
   */
  def regexSub(re: Regex)(replace: (Regex.MatchData => String)): String = {
    var offset = 0
    var out = new StringBuilder

    for (m <- re.findAllIn(wrapped).matchData) {
      if (m.start > offset) {
        out.append(wrapped.substring(offset, m.start))
      }

      out.append(replace(m))
      offset = m.end
    }

    if (offset < wrapped.length) {
      out.append(wrapped.substring(offset))
    }
    out.toString
  }

  private val QUOTE_RE = "[\u0000-\u001f\u007f-\uffff\\\\\"]".r

  /**
   * Quote a string so that unprintable chars (in ASCII) are represented by
   * C-style backslash expressions. For example, a raw linefeed will be
   * translated into <code>"\n"</code>. Control codes (anything below 0x20)
   * and unprintables (anything above 0x7E) are turned into either
   * <code>"\xHH"</code> or <code>"\\uHHHH"</code> expressions, depending on
   * their range. Embedded backslashes and double-quotes are also quoted.
   *
   * @return a quoted string, suitable for ASCII display
   */
  def quoteC(): String = {
    regexSub(QUOTE_RE) { m =>
      m.matched.charAt(0) match {
        case '\r' => "\\r"
        case '\n' => "\\n"
        case '\t' => "\\t"
        case '"' => "\\\""
        case '\\' => "\\\\"
        case c =>
          if (c <= 255) {
              "\\x%02x".format(c.asInstanceOf[Int])
          } else {
              "\\u%04x" format c.asInstanceOf[Int]
          }
      }
    }
  }

  // we intentionally don't unquote "\$" here, so it can be used to escape interpolation later.
  private val UNQUOTE_RE = """\\(u[\dA-Fa-f]{4}|x[\dA-Fa-f]{2}|[/rnt\"\\])""".r

  /**
   * Unquote an ASCII string that has been quoted in a style like
   * {@link #quoteC} and convert it into a standard unicode string.
   * <code>"\\uHHHH"</code> and <code>"\xHH"</code> expressions are unpacked
   * into unicode characters, as well as <code>"\r"</code>, <code>"\n"<code>,
   * <code>"\t"</code>, <code>"\\"<code>, and <code>'\"'</code>.
   *
   * @return an unquoted unicode string
   */
  def unquoteC() = {
    regexSub(UNQUOTE_RE) { m =>
      val ch = m.group(1).charAt(0) match {
        // holy crap! this is terrible:
        case 'u' => Character.valueOf(Integer.valueOf(m.group(1).substring(1), 16).asInstanceOf[Int].toChar)
        case 'x' => Character.valueOf(Integer.valueOf(m.group(1).substring(1), 16).asInstanceOf[Int].toChar)
        case 'r' => '\r'
        case 'n' => '\n'
        case 't' => '\t'
        case x => x
      }
      ch.toString
    }
  }
}

object string {
  implicit def stringToConfigurationString(s: String): ConfigurationString = new ConfigurationString(s)
}

