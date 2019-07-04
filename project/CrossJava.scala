/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.File

import scala.annotation.tailrec
import scala.collection.immutable.ListMap
import sbt._
import sbt.librarymanagement.SemanticSelector
import sbt.librarymanagement.VersionNumber
import akka.CrossJava.nullBlank

/*
 * Tools for discovering different Java versions,
 *
 * Some of these will be in sbt 1.3.0 (https://github.com/sbt/sbt/pull/4139 et al)
 * but are replicated here until that time
 */
case class JavaVersion(numbers: Vector[Long], vendor: Option[String]) {
  def numberStr: String = numbers.mkString(".")

  def withVendor(vendor: Option[String]) = copy(vendor = vendor)
  def withVendor(vendor: String) = copy(vendor = Option(vendor))
  def withNumbers(numbers: Vector[Long]) = copy(numbers = numbers)

  override def toString: String = {
    vendor.map(_ + "@").getOrElse("") + numberStr
  }
}
object JavaVersion {

  val specificationVersion: String = sys.props("java.specification.version")

  val version: String = sys.props("java.version")

  def isJdk8: Boolean =
    VersionNumber(specificationVersion).matchesSemVer(SemanticSelector(s"=1.8"))

  val isJdk11orHigher: Boolean =
    VersionNumber(specificationVersion).matchesSemVer(SemanticSelector(">=11"))

  def apply(version: String): JavaVersion = CrossJava.parseJavaVersion(version)
  def apply(numbers: Vector[Long], vendor: String): JavaVersion = new JavaVersion(numbers, Option(vendor))

  def notOnJdk8[T](values: Seq[T]): Seq[T] = if (isJdk8) Seq.empty[T] else values

  def sourceAndTarget(fullJavaHome: File): Seq[String] =
    if (isJdk8) Seq.empty
    else Seq("-source", "8", "-target", "8", "-bootclasspath", fullJavaHome + "/jre/lib/rt.jar")

}

object CrossJava extends AutoPlugin {
  object autoImport {
    val discoveredJavaHomes = settingKey[Map[String, File]]("Discovered Java home directories")
    val javaHomes = settingKey[Map[String, File]]("The user-defined additional Java home directories")
    val fullJavaHomes = settingKey[Map[String, File]]("Combines discoveredJavaHomes and custom javaHomes.")
    val targetSystemJdk = settingKey[Boolean]("Target the system JDK instead of building against JDK 8. When this is enabled resulting artifacts may not work on JDK 8!")
  }
  import autoImport._

  def targetJdkScalacOptions(targetSystemJdk: Boolean, fullJavaHomes: Map[String, File]): Seq[String] =
    selectOptions(
      targetSystemJdk,
      fullJavaHomes,
      Seq("-target:jvm-1.8"),
      // '-release 8' is not enough, for some reason we need the 8 rt.jar
      // explicitly. To test whether this has the desired effect, compile
      // akka-remote and check the invocation of 'ByteBuffer.clear()' in
      // EnvelopeBuffer.class with 'javap -c': it should refer to
      //""java/nio/ByteBuffer.clear:()Ljava/nio/Buffer" and not
      // "java/nio/ByteBuffer.clear:()Ljava/nio/ByteBuffer". Issue #27079
      (java8home: File) => Seq("-release", "8", "-javabootclasspath", java8home + "/jre/lib/rt.jar")
    )
  def targetJdkJavacOptions(targetSystemJdk: Boolean, fullJavaHomes: Map[String, File]): Seq[String] =
    selectOptions(
      targetSystemJdk,
      fullJavaHomes,
      Nil,
      // '-release 8' would be a neater option here, but is currently not an
      // option because it doesn't provide access to `sun.misc.Unsafe` #27079
      (java8home: File) => Seq("-source", "8", "-target", "8", "-bootclasspath", java8home + "/jre/lib/rt.jar")
  )

  private def selectOptions(targetSystemJdk: Boolean, fullJavaHomes: Map[String, File], jdk8options: Seq[String], jdk11options: File => Seq[String]): Seq[String] =
    if (targetSystemJdk)
      Nil
    else if (JavaVersion.isJdk8)
      jdk8options
    else fullJavaHomes.get("8") match {
      case Some(java8home) =>
        jdk11options(java8home)
      case None =>
        throw new MessageOnlyException("A JDK 8 installation was not found, but is required to build Akka. To target your system JDK, use the \"set every targetSystemJdk := true\" sbt command. Resulting artifacts may not work on JDK 8")
    }

  val crossJavaSettings = Seq(
    discoveredJavaHomes := CrossJava.discoverJavaHomes,
    javaHomes := ListMap.empty,
    fullJavaHomes := CrossJava.expandJavaHomes(discoveredJavaHomes.value ++ javaHomes.value),
    targetSystemJdk := false,
  )

  // parses jabaa style version number adopt@1.8
  def parseJavaVersion(version: String): JavaVersion = {
    def splitDot(s: String): Vector[Long] =
      Option(s) match {
        case Some(x) => x.split('.').toVector.filterNot(_ == "").map(_.toLong)
        case _       => Vector()
      }
    def splitAt(s: String): Vector[String] =
      Option(s) match {
        case Some(x) => x.split('@').toVector
        case _       => Vector()
      }
    splitAt(version) match {
      case Vector(vendor, rest) => JavaVersion(splitDot(rest), Option(vendor))
      case Vector(rest)         => JavaVersion(splitDot(rest), None)
      case _                    => sys.error(s"Invalid JavaVersion: $version")
    }
  }

  def discoverJavaHomes: ListMap[String, File] = {
    ListMap(JavaDiscoverConfig.configs.flatMap { _.javaHomes }.sortWith(versionOrder): _*)
  }

  sealed trait JavaDiscoverConf {
    def javaHomes: Vector[(String, File)]
  }

  def versionOrder(left: (_, File), right: (_, File)): Boolean =
    versionOrder(left._2.getName, right._2.getName)

  // Sort version strings, considering 1.8.0 < 1.8.0_45 < 1.8.0_121
  @tailrec
  def versionOrder(left: String, right: String): Boolean = {
    val Pattern = """.*?([0-9]+)(.*)""".r
    left match {
      case Pattern(leftNumber, leftRest) =>
        right match {
          case Pattern(rightNumber, rightRest) =>
            if (Integer.parseInt(leftNumber) < Integer.parseInt(rightNumber)) true
            else if (Integer.parseInt(leftNumber) > Integer.parseInt(rightNumber)) false
            else versionOrder(leftRest, rightRest)
          case _ =>
            false
        }
      case _ =>
        true
    }
  }

  object JavaDiscoverConfig {
    private val JavaHomeDir = """(java-|jdk-?|adoptopenjdk-)(1\.)?([0-9]+).*""".r

    class LinuxDiscoverConfig(base: File) extends JavaDiscoverConf {
      def javaHomes: Vector[(String, File)] =
        wrapNull(base.list()).collect {
          case dir @ JavaHomeDir(_, m, n) => JavaVersion(nullBlank(m) + n).toString -> (base / dir)
        }
    }

    class MacOsDiscoverConfig extends JavaDiscoverConf {
      val base: File = file("/Library") / "Java" / "JavaVirtualMachines"

      def javaHomes: Vector[(String, File)] =
        wrapNull(base.list()).collect {
          case dir @ JavaHomeDir(_, m, n) =>
            JavaVersion(nullBlank(m) + n).toString -> (base / dir / "Contents" / "Home")
        }
    }

    class WindowsDiscoverConfig extends JavaDiscoverConf {
      val base: File = file("C://Program Files/Java")

      def javaHomes: Vector[(String, File)] =
        wrapNull(base.list()).collect {
          case dir @ JavaHomeDir(_, m, n) => JavaVersion(nullBlank(m) + n).toString -> (base / dir)
        }
    }

    // See https://github.com/shyiko/jabba
    class JabbaDiscoverConfig extends JavaDiscoverConf {
      val base: File = Path.userHome / ".jabba" / "jdk"
      val JavaHomeDir = """([\w\-]+)\@(1\.)?([0-9]+).*""".r

      def javaHomes: Vector[(String, File)] =
        wrapNull(base.list()).collect {
          case dir @ JavaHomeDir(_, m, n) =>
            val v = JavaVersion(nullBlank(m) + n).toString
            if ((base / dir / "Contents" / "Home").exists) v -> (base / dir / "Contents" / "Home")
            else v -> (base / dir)
        }
    }

    class JavaHomeDiscoverConfig extends JavaDiscoverConf {
      def javaHomes: Vector[(String, File)] =
        sys.env
          .get("JAVA_HOME")
          .map(new java.io.File(_))
          .filter(_.exists())
          .flatMap { javaHome =>
            val base = javaHome.getParentFile
            javaHome.getName match {
              case dir @ JavaHomeDir(_, m, n) => Some(JavaVersion(nullBlank(m) + n).toString -> (base / dir))
              case _                          => None
            }
          }
          .toVector
    }

    val configs = Vector(
      new JabbaDiscoverConfig,
      new LinuxDiscoverConfig(file("/usr") / "java"),
      new LinuxDiscoverConfig(file("/usr") / "lib" / "jvm"),
      new MacOsDiscoverConfig,
      new WindowsDiscoverConfig,
      new JavaHomeDiscoverConfig)
  }

  def nullBlank(s: String): String =
    if (s eq null) ""
    else s

  // expand Java versions to 1-20 to 1.x, and vice versa to accept both "1.8" and "8"
  private val oneDot = Map((1L to 20L).toVector.flatMap { i =>
    Vector(Vector(i) -> Vector(1L, i), Vector(1L, i) -> Vector(i))
  }: _*)
  def expandJavaHomes(hs: Map[String, File]): Map[String, File] =
    hs.flatMap {
      case (k, v) =>
        val jv = JavaVersion(k)
        if (oneDot.contains(jv.numbers))
          Vector(k -> v, jv.withNumbers(oneDot(jv.numbers)).toString -> v)
        else Vector(k -> v)
    }

  def wrapNull(a: Array[String]): Vector[String] =
    if (a eq null) Vector()
    else a.toVector

}
