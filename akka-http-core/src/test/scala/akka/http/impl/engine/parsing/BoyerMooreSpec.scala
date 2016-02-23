/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import java.util.regex.Pattern
import scala.annotation.tailrec
import scala.util.Random
import org.scalatest.{ Matchers, WordSpec }
import akka.util.ByteString

class BoyerMooreSpec extends WordSpec with Matchers {

  "The Boyer Moore implementation" should {

    "correctly find all matches of a few handwritten string-search examples" in {
      findString("foo", "the foe in moofoo is foobar") shouldEqual Seq(14, 21)
      findString("ana", "bananas") shouldEqual Seq(1, 3)
      findString("anna", "bananas") shouldEqual Seq()
    }

    "perform identically to a regex search" in {
      val random = new Random()
      // the alphabet base is a random shuffle of 8 distinct alphanumeric chars
      val alphabetBase: IndexedSeq[Byte] = random.shuffle(0 to 255).take(8).map(_.toByte)

      val haystackLen = 1000
      (0 to 9) foreach { run ⇒
        val alphabet = alphabetBase.take(4 + random.nextInt(5)) // 4 to 8 distinct alphanumeric chars
        val randomAlphabetChars = Stream.continually(alphabet(random.nextInt(alphabet.length)))
        def randomBytes(num: Int): ByteString = ByteString(randomAlphabetChars.take(num): _*)
        val haystack = randomBytes(haystackLen)
        val needle = randomBytes(run / 3 + 3) // 3 to 6 random alphabet chars

        val bmFinds = find(needle, haystack, skipFindsThatStartInFinds = true)
        val reFinds = findWithRegex(needle, haystack)
        if (bmFinds != reFinds) {
          def showBytes(bs: Seq[Byte]): String = bs.map(b ⇒ (b & 0xff).formatted("%02x")).mkString(" ")
          def len(num: Int) = num * 2 + math.max(0, num - 1)

          def showFind(ix: Int): String = {
            val startIdx = math.max(ix - 8, 0)
            val endIdx = math.min(ix + needle.length + 8, haystack.length)

            s"""...${showBytes(haystack.drop(startIdx).take(endIdx - startIdx))}...
               |${" " * (3 + math.min(8, ix) * 3)}${"^" * len(needle.length)}
               |""".stripMargin
          }
          val foundOnlyByBM = bmFinds.filterNot(reFinds.contains).map(showFind).mkString
          val foundOnlyByRE = reFinds.filterNot(bmFinds.contains).map(showFind).mkString
          fail(s"""alphabet: ${showBytes(alphabet)}
                   |needle: ${showBytes(needle)}
                   |found only by boyer moore:
                   |$foundOnlyByBM
                   |found only by regex:
                   |$foundOnlyByRE
                 """.stripMargin)
        }
      }
    }
  }

  def findString(needle: String, haystack: String, skipFindsThatStartInFinds: Boolean = false): Seq[Int] =
    find(ByteString(needle), ByteString(haystack), skipFindsThatStartInFinds)

  def find(needle: ByteString, haystack: ByteString, skipFindsThatStartInFinds: Boolean = false): Seq[Int] = {
    val boyerMoore = new BoyerMoore(needle.toArray[Byte])
    @tailrec def rec(offset: Int, result: Seq[Int]): Seq[Int] = {
      val ix =
        try boyerMoore.nextIndex(haystack, offset)
        catch { case NotEnoughDataException ⇒ -1 }
      if (ix >= 0) rec(if (skipFindsThatStartInFinds) ix + needle.length else ix + 1, result :+ ix) else result
    }
    rec(0, Seq.empty)
  }

  def findWithRegex(needle: ByteString, haystack: ByteString): Seq[Int] =
    Pattern.quote(needle.map(_.toChar).mkString).r.findAllMatchIn(haystack.map(_.toChar).mkString).map(_.start).toSeq
}
