/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import scala.collection.immutable
import java.io.IOException
import java.util.Arrays
import java.util.jar.Attributes
import java.util.jar.Manifest

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.event.Logging

/**
 * Akka extension that extracts [[ManifestInfo.Version]] information from META-INF/MANIFEST.MF in jar files
 * on the classpath of the `ClassLoader` of the `ActorSystem`.
 */
object ManifestInfo extends ExtensionId[ManifestInfo] with ExtensionIdProvider {
  private val ImplTitle = "Implementation-Title"
  private val ImplVersion = "Implementation-Version"
  private val ImplVendor = "Implementation-Vendor-Id"

  private val BundleName = "Bundle-Name"
  private val BundleVersion = "Bundle-Version"
  private val BundleVendor = "Bundle-Vendor"

  private val knownVendors = Set(
    "com.typesafe.akka",
    "com.lightbend.akka",
    "Lightbend Inc.",
    "Lightbend",
    "com.lightbend.lagom",
    "com.typesafe.play"
  )

  override def get(system: ActorSystem): ManifestInfo = super.get(system)

  override def lookup(): ManifestInfo.type = ManifestInfo

  override def createExtension(system: ExtendedActorSystem): ManifestInfo = new ManifestInfo(system)

  /**
   * Comparable version information
   */
  final class Version(val version: String) extends Comparable[Version] {
    private val (numbers: Array[Int], rest: String) = {
      val numbers = new Array[Int](3)
      val segments: Array[String] = version.split("[.-]")
      var segmentPos = 0
      var numbersPos = 0
      while (numbersPos < 3) {
        if (segmentPos < segments.length) try {
          numbers(numbersPos) = segments(segmentPos).toInt
          segmentPos += 1
        } catch {
          case _: NumberFormatException ⇒
            // This means that we have a trailing part on the version string and
            // less than 3 numbers, so we assume that this is a "newer" version
            numbers(numbersPos) = Integer.MAX_VALUE
        }
        numbersPos += 1
      }

      val rest: String =
        if (segmentPos >= segments.length) ""
        else String.join("-", Arrays.asList(Arrays.copyOfRange(segments, segmentPos, segments.length): _*))

      (numbers, rest)
    }

    override def compareTo(other: Version): Int = {
      var diff = 0
      diff = numbers(0) - other.numbers(0)
      if (diff == 0) {
        diff = numbers(1) - other.numbers(1)
        if (diff == 0) {
          diff = numbers(2) - other.numbers(2)
          if (diff == 0) {
            diff = rest.compareTo(other.rest)
          }
        }
      }
      diff
    }

    override def equals(o: Any): Boolean = o match {
      case v: Version ⇒ compareTo(v) == 0
      case _          ⇒ false
    }

    override def hashCode(): Int = {
      var result = HashCode.SEED
      result = HashCode.hash(result, numbers(0))
      result = HashCode.hash(result, numbers(1))
      result = HashCode.hash(result, numbers(2))
      result = HashCode.hash(result, rest)
      result
    }

    override def toString: String = version
  }
}

/**
 * Utility that extracts [[ManifestInfo#Version]] information from META-INF/MANIFEST.MF in jar files on the classpath.
 * Note that versions can only be found in ordinary jar files, for example not in "fat jars' assembled from
 * many jar files.
 */
final class ManifestInfo(val system: ExtendedActorSystem) extends Extension {
  import ManifestInfo._

  /**
   * Versions of artifacts from known vendors.
   */
  val versions: Map[String, Version] = {

    var manifests = Map.empty[String, Version]

    try {
      val resources = system.dynamicAccess.classLoader.getResources("META-INF/MANIFEST.MF")
      while (resources.hasMoreElements()) {
        val ios = resources.nextElement().openStream()
        try {
          val manifest = new Manifest(ios)
          val attributes = manifest.getMainAttributes
          val title = attributes.getValue(new Attributes.Name(ImplTitle)) match {
            case null ⇒ attributes.getValue(new Attributes.Name(BundleName))
            case t    ⇒ t
          }
          val version = attributes.getValue(new Attributes.Name(ImplVersion)) match {
            case null ⇒ attributes.getValue(new Attributes.Name(BundleVersion))
            case v    ⇒ v
          }
          val vendor = attributes.getValue(new Attributes.Name(ImplVendor)) match {
            case null ⇒ attributes.getValue(new Attributes.Name(BundleVendor))
            case v    ⇒ v
          }

          if (title != null
            && version != null
            && vendor != null
            && knownVendors(vendor)) {
            manifests = manifests.updated(title, new Version(version))
          }
        } finally {
          ios.close()
        }
      }
    } catch {
      case ioe: IOException ⇒
        Logging(system, getClass).warning("Could not read manifest information. {}", ioe)
    }
    manifests
  }

  /**
   * Verify that the version is the same for all given artifacts.
   */
  def checkSameVersion(productName: String, dependencies: immutable.Seq[String], logWarning: Boolean): Boolean = {
    val filteredVersions = versions.filterKeys(dependencies.toSet)
    val values = filteredVersions.values.toSet
    if (values.size > 1) {
      if (logWarning) {
        val conflictingVersions = values.mkString(", ")
        val fullInfo = filteredVersions.map { case (k, v) ⇒ s"$k:$v" }.mkString(", ")
        val highestVersion = values.max
        Logging(system, getClass).warning(
          "Detected possible incompatible versions on the classpath. " +
            s"Please note that a given $productName version MUST be the same across all modules of $productName " +
            "that you are using, e.g. if you use [{}] all other modules that are released together MUST be of the " +
            "same version. Make sure you're using a compatible set of libraries. " +
            "Possibly conflicting versions [{}] in libraries [{}]",
          highestVersion, conflictingVersions, fullInfo)
      }
      false
    } else
      true
  }

}
