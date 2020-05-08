/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pki.pem

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAMultiPrimePrivateCrtKeySpec
import java.security.spec.RSAOtherPrimeInfo
import java.security.spec.RSAPrivateCrtKeySpec

import akka.pki.pem.PEMDecoder.PEMData
import com.hierynomus.asn1.ASN1InputStream
import com.hierynomus.asn1.encodingrules.der.DERDecoder
import com.hierynomus.asn1.types.constructed.ASN1Sequence
import com.hierynomus.asn1.types.primitive.ASN1Integer

object PEMPrivateKeyLoader {

  def load(keyFile: String): Either[String, PrivateKey] = {
    val pemData = new String(Files.readAllBytes(new File(keyFile).toPath))

    // Decode from PEM into binary
    PEMDecoder.decode(pemData) match {
      case Left(error) =>
        Left(error)
      case Right(PEMData("RSA PRIVATE KEY", bytes)) =>
        Right(loadPkcs1PrivateKey(bytes))
      case Right(PEMData("PRIVATE KEY", bytes)) =>
        Right(loadPkcs8PrivateKey(bytes))
      case Right(PEMData(unknown, _)) =>
        Left(s"Don't know how to read a private key from PEM data with label [$unknown]")
    }
  }

  /**
    * Yay for rolling our own crypto because the JDK doesn't understand the formats that EVERY SINGLE OTHER PLATFORM DOES.
    */
  private def loadPkcs1PrivateKey(bytes: Array[Byte]) = {
    val derInputStream = new ASN1InputStream(new DERDecoder, bytes)
    // Here's the specification: https://tools.ietf.org/html/rfc3447#appendix-A.1.2
    val sequence = derInputStream.readObject[ASN1Sequence]()
    val version = getInteger(sequence, 0, "version").intValueExact()
    if (version < 0 || version > 1) {
      throw new IllegalArgumentException(s"Unsupported PKCS1 version: $version")
    }
    val modulus = getInteger(sequence, 1, "modulus")
    val publicExponent = getInteger(sequence, 2, "publicExponent")
    val privateExponent = getInteger(sequence, 3, "privateExponent")
    val prime1 = getInteger(sequence, 4, "prime1")
    val prime2 = getInteger(sequence, 5, "prime2")
    val exponent1 = getInteger(sequence, 6, "exponent1")
    val exponent2 = getInteger(sequence, 7, "exponent2")
    val coefficient = getInteger(sequence, 8, "coefficient")

    val keySpec = if (version == 0) {
      new RSAPrivateCrtKeySpec(modulus, publicExponent, privateExponent, prime1, prime2, exponent1, exponent2,
        coefficient)
    } else {
      // Does anyone even use multi-primes? Who knows, maybe this code will never be used. Anyway, I guess it will work,
      // the spec isn't exactly complicated.
      val otherPrimeInfosSequence = getSequence(sequence, 9, "otherPrimeInfos")
      val otherPrimeInfos = (for (i <- 0 until otherPrimeInfosSequence.size()) yield {
        val name = s"otherPrimeInfos[$i]"
        val seq = getSequence(otherPrimeInfosSequence, i, name)
        val prime = getInteger(seq, 0, s"$name.prime")
        val exponent = getInteger(seq, 1, s"$name.exponent")
        val coefficient = getInteger(seq, 2, s"$name.coefficient")
        new RSAOtherPrimeInfo(prime, exponent, coefficient)
      }).toArray
      new RSAMultiPrimePrivateCrtKeySpec(modulus, publicExponent, privateExponent, prime1, prime2, exponent1, exponent2,
        coefficient, otherPrimeInfos)
    }

    val keyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePrivate(keySpec)
  }

  private def getInteger(sequence: ASN1Sequence, index: Int, name: String): BigInteger = {
    sequence.get(index) match {
      case integer: ASN1Integer => integer.getValue
      case other =>
        throw new IllegalArgumentException(s"Expected integer tag for $name at index $index, but got: ${other.getTag}")
    }
  }

  private def getSequence(sequence: ASN1Sequence, index: Int, name: String): ASN1Sequence = {
    sequence.get(index) match {
      case seq: ASN1Sequence => seq
      case other =>
        throw new IllegalArgumentException(s"Expected sequence tag for $name at index $index, but got: ${other.getTag}")
    }
  }

  private def loadPkcs8PrivateKey(bytes: Array[Byte]) = {
    val keySpec = new PKCS8EncodedKeySpec(bytes)
    val keyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePrivate(keySpec)
  }


}