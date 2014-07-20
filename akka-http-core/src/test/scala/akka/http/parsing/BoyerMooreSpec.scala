/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import akka.parboiled2.CharPredicate

import scala.annotation.tailrec
import org.scalatest.{ Matchers, WordSpec }
import akka.util.ByteString
import akka.http.util._

import scala.util.Random

class BoyerMooreSpec extends WordSpec with Matchers {

  "The Boyer Moore implementation" should {

    "correctly find all matches of a few handwritten string-search examples" in {
      find("foo", "the foe in moofoo is foobar") shouldEqual Seq(14, 21)
      find("ana", "bananas") shouldEqual Seq(1, 3)
      find("anna", "bananas") shouldEqual Seq()
    }

    "perform identically to a regex search" in {
      val random = new Random()
      // the alphabet base is a random shuffle of 8 distinct alphanumeric chars
      val alphabetBase = random.shuffle('0' to 'z' filter CharPredicate.AlphaNum) take 8
      val haystackLen = 1000
      (0 to 9) foreach { run ⇒
        val alphabet = alphabetBase.take(4 + random.nextInt(5)).mkString // 4 to 8 distinct alphanumeric chars
        val randomAlphabetChars = Stream.continually(alphabet.charAt(random.nextInt(alphabet.length)))
        val haystack = randomAlphabetChars.take(haystackLen).mkString
        val needle = randomAlphabetChars.take(run / 3 + 3).mkString // 3 to 6 random alphabet chars

        val bmFinds = find(needle, haystack, skipFindsThatStartInFinds = true)
        val reFinds = findWithRegex(needle, haystack)
        if (bmFinds != reFinds) {
          def showFind(ix: Int): String =
            s"""...${haystack.substring(math.max(ix - 8, 0), math.min(ix + needle.length + 8, haystack.length))}...
               |${" " * (3 + math.min(ix - 8, 8))}${"^" * needle.length}
               |""".stripMargin
          val foundOnlyByBM = bmFinds.filterNot(reFinds.contains).map(showFind).mkString
          val foundOnlyByRE = reFinds.filterNot(bmFinds.contains).map(showFind).mkString
          fail(s"""alphabet: $alphabet
                   |needle: $needle
                   |found only by boyer moore:
                   |$foundOnlyByBM
                   |found only by regex:
                   |$foundOnlyByRE
                 """.stripMargin)
        }
      }
    }
  }

  def find(needle: String, haystack: String, skipFindsThatStartInFinds: Boolean = false): Seq[Int] = {
    val boyerMoore = new BoyerMoore(needle.asciiBytes)
    @tailrec def rec(offset: Int, result: Seq[Int]): Seq[Int] = {
      val ix =
        try boyerMoore.nextIndex(ByteString(haystack), offset)
        catch { case NotEnoughDataException ⇒ -1 }
      if (ix >= 0) rec(if (skipFindsThatStartInFinds) ix + needle.length else ix + 1, result :+ ix) else result
    }
    rec(0, Seq.empty)
  }

  def findWithRegex(needle: String, haystack: String): Seq[Int] =
    needle.r.findAllMatchIn(haystack).map(_.start).toSeq
}
