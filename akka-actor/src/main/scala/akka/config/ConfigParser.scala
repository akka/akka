/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 *
 * Based on Configgy by Robey Pointer.
 *   Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package akka.config

import scala.collection.mutable.Stack
import scala.util.parsing.combinator._
import scala.util.parsing.input.CharSequenceReader
import akka.config.string._


/**
 * An exception thrown when parsing a config file, if there was an error
 * during parsing. The `reason` string will contain the parsing
 * error details.
 */
class ParseException(reason: String, cause: Throwable) extends Exception(reason, cause) {
  def this(reason: String) = this(reason, null)
  def this(cause: Throwable) = this(null, cause)
}


private[config] class ConfigParser(var attr: Attributes, val importer: Importer) extends RegexParsers {

  val sections = new Stack[String]
  var prefix = ""

  // Stack reversed iteration order from 2.7 to 2.8!!
  def sectionsString = sections.toList.reverse.mkString(".")

  // tokens
  override val whiteSpace = """(\s+|#[^\n]*\n)+""".r
  val numberToken: Parser[String] = """-?\d+(\.\d+)?""".r
  val stringToken: Parser[String] = ("\"" + """([^\\\"]|\\[^ux]|\\\n|\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2})*""" + "\"").r
  val identToken: Parser[String] = """([\da-zA-Z_][-\w]*)(\.[a-zA-Z_][-\w]*)*""".r
  val assignToken: Parser[String] = """=|\?=""".r
  val tagNameToken: Parser[String] = """[a-zA-Z][-\w]*""".r


  def root = rep(includeFile | includeOptFile | assignment | toggle | sectionOpen | sectionClose |
                 sectionOpenBrace | sectionCloseBrace)

  def includeFile = "include" ~> string ^^ {
    case filename: String =>
      new ConfigParser(attr.makeAttributes(sectionsString), importer) parse importer.importFile(filename)
  }

  def includeOptFile = "include?" ~> string ^^ {
    case filename: String =>
      new ConfigParser(attr.makeAttributes(sections.mkString(".")), importer) parse importer.importFile(filename, false)
  }

  def assignment = identToken ~ assignToken ~ value ^^ {
    case k ~ a ~ v => if (a match {
      case "=" => true
      case "?=" => ! attr.contains(prefix + k)
    }) v match {
      case x: Long => attr(prefix + k) = x
      case x: String => attr(prefix + k) = x
      case x: Array[String] => attr(prefix + k) = x
      case x: Boolean => attr(prefix + k) = x
    }
  }

  def toggle = identToken ~ trueFalse ^^ { case k ~ v => attr(prefix + k) = v }

  def sectionOpen = "<" ~> tagNameToken ~ rep(tagAttribute) <~ ">" ^^ {
    case name ~ attrList => openBlock(name, attrList)
  }
  def tagAttribute = opt(whiteSpace) ~> (tagNameToken <~ "=") ~ string ^^ { case k ~ v => (k, v) }
  def sectionClose = "</" ~> tagNameToken <~ ">" ^^ { name => closeBlock(Some(name)) }

  def sectionOpenBrace = tagNameToken ~ opt("(" ~> rep(tagAttribute) <~ ")") <~ "{" ^^ {
    case name ~ attrListOption => openBlock(name, attrListOption.getOrElse(Nil))
  }
  def sectionCloseBrace = "}" ^^ { x => closeBlock(None) }

  private def openBlock(name: String, attrList: List[(String, String)]) = {
    val parent = if (sections.size > 0) attr.makeAttributes(sectionsString) else attr
    sections push name
    prefix = sectionsString + "."
    val newBlock = attr.makeAttributes(sectionsString)
    for ((k, v) <- attrList) k match {
      case "inherit" =>
        newBlock.inheritFrom = Some(if (parent.getConfigMap(v).isDefined) parent.makeAttributes(v) else attr.makeAttributes(v))
      case _ =>
        throw new ParseException("Unknown block modifier")
    }
  }

  private def closeBlock(name: Option[String]) = {
    if (sections.isEmpty) {
      failure("dangling close tag")
    } else {
      val last = sections.pop
      if (name.isDefined && last != name.get) {
        failure("got closing tag for " + name.get + ", expected " + last)
      } else {
        prefix = if (sections.isEmpty) "" else sectionsString + "."
      }
    }
  }


  def value: Parser[Any] = number | string | stringList | trueFalse
  def number = numberToken ^^ { x => if (x.contains('.')) x else x.toLong }
  def string = stringToken ^^ { s => attr.interpolate(prefix, s.substring(1, s.length - 1).unquoteC) }
  def stringList = "[" ~> repsep(string | numberToken, opt(",")) <~ (opt(",") ~ "]") ^^ { list => list.toArray }
  def trueFalse: Parser[Boolean] = ("(true|on)".r ^^ { x => true }) | ("(false|off)".r ^^ { x => false })


  def parse(in: String): Unit = {
    parseAll(root, in) match {
      case Success(result, _) => result
      case x @ Failure(msg, z) => throw new ParseException(x.toString)
      case x @ Error(msg, _) => throw new ParseException(x.toString)
    }
  }
}
