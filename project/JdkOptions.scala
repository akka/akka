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

object JdkOptions extends AutoPlugin {
  object autoImport {
    val targetSystemJdk = settingKey[Boolean]("Target the system JDK instead of building against JDK 8. When this is enabled resulting artifacts may not work on JDK 8!")
  }
  import autoImport._

  val specificationVersion: String = sys.props("java.specification.version")

  val isJdk8: Boolean =
    VersionNumber(specificationVersion).matchesSemVer(SemanticSelector(s"=1.8"))
  val isJdk11orHigher: Boolean =
    VersionNumber(specificationVersion).matchesSemVer(SemanticSelector(">=11"))

  def notOnJdk8[T](values: Seq[T]): Seq[T] = if (isJdk8) Seq.empty[T] else values

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
    else if (isJdk8)
      jdk8options
    else fullJavaHomes.get("8") match {
      case Some(java8home) =>
        jdk11options(java8home)
      case None =>
        throw new MessageOnlyException("A JDK 8 installation was not found, but is required to build Akka. To target your system JDK, use the \"set every targetSystemJdk := true\" sbt command. Resulting artifacts may not work on JDK 8")
    }

  val targetJdkSettings = Seq(
    targetSystemJdk := false,
  )
}
