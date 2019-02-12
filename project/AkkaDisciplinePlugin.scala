package akka

import sbt._, Keys._

object CommonOptions {

  val encodingSettings = Seq(
    "-encoding", "UTF-8")                // Specify character encoding used by source files.

  val commonScalacOptions = encodingSettings ++ Seq(
    "-Xlog-reflective-calls",
    "-feature",                          // Emit warning and location for usages of features that should be imported explicitly.
    "-unchecked",                        // Enable additional warnings where generated code depends on assumptions.
    "-deprecation",                      // Emit warning and location for usages of deprecated APIs.
    "-explaintypes",                     // Explain type errors in more detail.
    "-language:existentials",            // Existential types (besides wildcard types) can be written and inferred
    "-language:experimental.macros",     // Allow macro definition (besides implementation and application)
    "-language:higherKinds",             // Allow higher-kinded types
    "-language:implicitConversions")     // Allow definition of implicit functions called views

  // -XDignore.symbol.file suppresses sun.misc.Unsafe warnings
  val commonJavacOptions = encodingSettings ++ Seq(
    "-Xlint:unchecked",
    "-XDignore.symbol.file")

  /** Makes sure that, even when compiling with a jdk version greater than 8, the resulting jar will not refer to
    * methods not found in jdk8. To test whether this has the desired effect, compile akka-remote and check the
    * invocation of 'ByteBuffer.clear()' in EnvelopeBuffer.class with 'javap -c': it should refer to
    * "java/nio/ByteBuffer.clear:()Ljava/nio/Buffer" and not "java/nio/ByteBuffer.clear:()Ljava/nio/ByteBuffer":
    * `scalacOptions in Compile ++= javaTarget`
    */
  def javaTarget(scalaBinary: String, fullJavaHomes: String): Seq[String] =
    if (Versions.isJdk1dot)
      Seq("-target:jvm-1.8")
    else if (scalaBinary == "2.11")
      Seq("-target:jvm-1.8", "-javabootclasspath", fullJavaHomes)
    else
      Seq("-release", "8", "-javabootclasspath", fullJavaHomes) // -release 8 is not enough, for some reason we need the 8 rt.jar explicitly #25330

  def javaSource(fullJavaHomes: String): Seq[String] =
    if (Versions.isJdk1dot)
      Nil
    else
      Seq("-source", "8", "-target", "8", "-bootclasspath", fullJavaHomes)

}

object AkkaDisciplinePlugin extends AutoPlugin {

  import scoverage.ScoverageKeys.{coverageFailOnMinimum, coverageHighlighting, coverageMinimum}
  import wartremover._
  import akka.Versions.isScalaMinor

  override def trigger: PluginTrigger = allRequirements // noTrigger
  override lazy val projectSettings = disciplineSettings

  lazy val scoverageSettings = Seq(
    coverageMinimum := 60, // TODO set during review
    coverageFailOnMinimum := false,
    coverageHighlighting := true)

  /** As of v2.4.1 there are 37 built-in Warts.
    * Module `akka-actor` currently requires 34 Warts removed.
    * With Warts.all or Warts.unsafe, `akka-actor` has 3793 errors.
    *
    * Take on one at a time where possible.
    * For some, with akka-actor vs typed, e.g. Wart.Any, just accept it and move on.
    * To catch as many issues as we can in compile-time, we can also enable / disable
    * by module as well, as a work-around.
    */
  lazy val wartremoverSettings = Seq(
    wartremoverErrors in (Compile, compile) ++= Warts.allBut(
      Wart.MutableDataStructures,
      Wart.ExplicitImplicitTypes,
      Wart.LeakingSealed,
      Wart.Any,
      Wart.AnyVal,
      Wart.Var,
      Wart.Throw,
      Wart.Null,
      Wart.FinalVal,
      Wart.FinalCaseClass,
      Wart.AsInstanceOf,
      Wart.IsInstanceOf,
      Wart.DefaultArguments,
      Wart.Overloading,
      Wart.Recursion,
      Wart.NonUnitStatements,
      Wart.ImplicitConversion,
      Wart.ImplicitParameter,
      Wart.Nothing,
      Wart.PublicInference,
      Wart.Equals,
      Wart.Return,
      Wart.Enumeration,
      Wart.TraversableOps,
      Wart.TryPartial,
      Wart.OptionPartial,
      Wart.Option2Iterable,
      Wart.ArrayEquals,
      Wart.ToString,
      Wart.StringPlusAny,
      Wart.While,
      Wart.Product,
      Wart.JavaSerializable,
      Wart.Serializable),
    wartremoverErrors in Test ++= Nil) // in progress

  lazy val disciplineSettings =
    scoverageSettings ++
      wartremoverSettings ++ Seq(
      scalacOptions := disciplineScalacOptions,
      scalacOptions in (Compile, compile) ++= additionsFor(scalaVersion.value),
      scalacOptions in (Compile, compile) --= removalsFor(scalaVersion.value),
      scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"))

  // all, which later get filtered by scalaVersion
  val disciplineScalacOptions = Seq(
    "-Xcheckinit",                       // Wrap field accessors to throw an exception on uninitialized access.
    "-Xfuture",                          // Turn on future language features.
    // TODO "-Yinline-warnings",
    "-Yinline-warnings",
    "-Xlint:_",
    "-Ypartial-unification",             // Enable partial unification in type constructor inference TODO version specific
    "-Ywarn-dead-code",                  // Warn when dead code is identified.
    "-Ywarn-extra-implicit",             // Warn when more than one implicit parameter section is defined.
    "-Ywarn-inaccessible",               // Warn about inaccessible types in method signatures.
    "-Ywarn-infer-any",                  // Warn when a type argument is inferred to be `Any`.
    "-Ywarn-nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Ywarn-nullary-unit",               // Warn when nullary methods return Unit.
    "-Ywarn-numeric-widen",              // Warn when numerics are widened.
    "-Ywarn-unused",
    "-Ywarn-value-discard")              // Warn when non-Unit expression results are unused.

  private def removalsFor(scalaVersion: String): Seq[String] =
    scalaVersion match {
      case v if isScalaMinor("==", 13, v) =>
        Seq("-Ywarn-unused")             // https://github.com/akka/akka/issues/26119: breaks 'sbt ++2.13.0-M5 akka-actor/doc'

      case v if isScalaMinor("<", 13, v) =>
        Seq(
          "-Yno-adapted-args",           // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver.
          "-Xfatal-warnings")            // Fail the compilation if there are any warnings.

      case v if isScalaMinor("<", 12, v) =>
        Seq("-Ypartial-unification")
      case _ =>
        Nil
    }

  private def additionsFor(scalaVersion: String): Seq[String] =
    if (isScalaMinor(">=", 13, scalaVersion))
      Seq("-Ymacro-annotations")         // Enable support for macro annotations, formerly in macro paradise.
    else
      Nil

  // TODO ?
  lazy val flagsNoAutoImports = Seq(
    "-Yno-imports"                       // No automatic imports at all; all symbols must be imported explicitly
  )

  /* if we ever need more detail instead:
   "-Xlint:-unused,_",
   "-Xlint:adapted-args",               // Warn if an argument list is modified to match the receiver.
   "-Xlint:by-name-right-associative",  // By-name parameter of right associative operator.
   "-Xlint:constant",                   // Evaluation of a constant arithmetic expression results in an error.
   "-Xlint:delayedinit-select",         // Selecting member of DelayedInit.
   "-Xlint:doc-detached",               // A Scaladoc comment appears to be detached from its element.
   "-Xlint:inaccessible",               // Warn about inaccessible types in method signatures.
   "-Xlint:infer-any",                  // Warn when a type argument is inferred to be `Any`.
   "-Xlint:missing-interpolator",       // A string literal appears to be missing an interpolator id.
   "-Xlint:nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
   "-Xlint:nullary-unit",               // Warn when nullary methods return Unit.
   "-Xlint:option-implicit",            // Option.apply used implicit view.
   "-Xlint:package-object-classes",     // Class or object defined in package object.
   "-Xlint:poly-implicit-overload",     // Parameterized overloaded implicit methods are not visible as view bounds.
   "-Xlint:private-shadow",             // A private field (or class parameter) shadows a superclass field.
   "-Xlint:stars-align",                // Pattern sequence wildcard must align with sequence component.
   "-Xlint:type-parameter-shadow",      // A local type parameter shadows a type already in scope.
   "-Xlint:unsound-match",              // Pattern match may not be typesafe.
   */

  /* if we ever need more detail instead:
    "-Ywarn-unused:implicits",           // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports",             // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals",              // Warn if a local definition is unused.
    "-Ywarn-unused:params",              // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars",             // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates",            // Warn if a private member is unused.
    */
}
