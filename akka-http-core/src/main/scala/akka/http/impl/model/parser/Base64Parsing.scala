/*
 * Copyright (C) 2009-2016 Mathias Doenitz, Alexander Myltsev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.impl.model.parser

import akka.parboiled2.util.Base64
import akka.parboiled2._

/**
 * Rules for parsing Base-64 encoded strings.
 */
private[parser] trait Base64Parsing { this: Parser ⇒
  import Base64Parsing._

  /**
   * Parses an RFC4045-encoded string and decodes it onto the value stack.
   */
  def rfc2045String: Rule1[Array[Byte]] = base64StringOrBlock(rfc2045Alphabet, rfc2045StringDecoder)

  /**
   * Parses an RFC4045-encoded string potentially containing newlines and decodes it onto the value stack.
   */
  def rfc2045Block: Rule1[Array[Byte]] = base64StringOrBlock(rfc2045Alphabet, rfc2045BlockDecoder)

  /**
   * Parses a org.parboiled2.util.Base64.custom()-encoded string and decodes it onto the value stack.
   */
  def base64CustomString: Rule1[Array[Byte]] = base64StringOrBlock(customAlphabet, customStringDecoder)

  /**
   * Parses a org.parboiled2.util.Base64.custom()-encoded string potentially containing newlines
   * and decodes it onto the value stack.
   */
  def base64CustomBlock: Rule1[Array[Byte]] = base64StringOrBlock(customAlphabet, customBlockDecoder)

  /**
   * Parses a BASE64-encoded string with the given alphabet and decodes it onto the value
   * stack using the given codec.
   */
  def base64StringOrBlock(alphabet: CharPredicate, decoder: Decoder): Rule1[Array[Byte]] = {
    val start = cursor
    rule {
      oneOrMore(alphabet) ~ run {
        decoder(input.sliceCharArray(start, cursor)) match {
          case null  ⇒ MISMATCH
          case bytes ⇒ push(bytes)
        }
      }
    }
  }
}

object Base64Parsing {
  type Decoder = Array[Char] ⇒ Array[Byte]

  val rfc2045Alphabet = CharPredicate(Base64.rfc2045().getAlphabet).asMaskBased
  val customAlphabet = CharPredicate(Base64.custom().getAlphabet).asMaskBased

  val rfc2045StringDecoder: Decoder = decodeString(Base64.rfc2045())
  val customStringDecoder: Decoder = decodeString(Base64.custom())

  val rfc2045BlockDecoder: Decoder = decodeBlock(Base64.rfc2045())
  val customBlockDecoder: Decoder = decodeBlock(Base64.custom())

  def decodeString(codec: Base64)(chars: Array[Char]): Array[Byte] = codec.decodeFast(chars)
  def decodeBlock(codec: Base64)(chars: Array[Char]): Array[Byte] = codec.decode(chars)
}