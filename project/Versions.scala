package akka

import sbt._
import Keys._

object Versions {

  val Scala: Seq[String] = Seq("2.12.8", "2.11.12", "2.13.0-M5")

  def javaVersion: String = sys.props("java.version")

  val Aeron = "1.15.1"
  val Camel = "2.17.7"
  val Config = "1.3.3"
  val DropwizardMetrics = "3.2.5"
  val Jctools = "2.1.2"
  val Lmdb = "0.6.1"
  val Netty = "3.10.6.Final"
  val OsgiCore = "4.3.1"
  val OsgiCompendium = "4.3.1"
  val ReactiveStreams = "1.0.2"
  val SslConfig = "0.3.7"
  val Slf4j = "1.7.25"
  val ScalaXml = "1.0.6"
  val Sigar = "1.6.4"
  val StmDefault = "0.9"

  // test
  val CommonsCodec = "1.11"
  val CommonsIo = "2.6"
  val CommonsMath = "2.2"
  val DockerClient = "8.13.1"
  val HdrHistogram = "2.1.10"
  val Iq80LevelDB = "0.10"
  val JaxbApi = "2.3.0"
  val Java8CompatScala13 = "0.9.0"
  val Java8CompatScala12 = "0.8.0"
  val Java8CompatOlder = "0.7.0"
  val JavaxActivation = "1.2.0"
  val Jimfs = "1.1"
  val Junit = "4.12"
  val KamonSigar = "1.6.6-rev002"
  val LatencyUtils = "1.0.5"
  val LevelDBJni = "1.8"
  val Log4j = "1.2.17"
  val Logback = "1.2.3"
  val Mockito = "2.19.1"
  val Pojosr = "0.2.1"
  val ReactiveStreamsTck = "1.0.2"
  val ScalaTestDefault = "1.14.0"
  val ScalaTestPre2_12 = "1.13.2" // 1.13.5 TODO verify then upgrade
  val ScalaTest = "3.0.6-SNAP5"
  val TinyBundles = "1.0.0"

  // docs
  val Gson = "2.8.5"
  val SprayJson = "1.3.4"

  def java8Compat(scalaVersion: String): String = scalaVersion match {
    case v if isScalaMinor(">=", 13, v) => Java8CompatScala13
    case v if isScalaMinor("==", 12, v) => Java8CompatScala12
    case _ => Java8CompatOlder
  }

  def scalaCheck(scalaVersion: String): String = scalaVersion match {
    case v if isScalaMinor(">=", 12, v) => ScalaTestDefault
    case _ => ScalaTestPre2_12
  }

  lazy val scalaTestVersion = settingKey[String]("The version of ScalaTest to use.")
  lazy val scalaStmVersion = settingKey[String]("The version of ScalaSTM to use.")
  lazy val scalaCheckVersion = settingKey[String]("The version of ScalaCheck to use.")
  lazy val java8CompatVersion = settingKey[String]("The version of scala-java8-compat to use.")

  lazy val versionSettings = {
    Seq(
      crossScalaVersions := Scala,
      scalaVersion := sys.props.getOrElse("akka.build.scalaVersion", crossScalaVersions.value.head),
      scalaStmVersion := sys.props.getOrElse("akka.build.scalaStmVersion", StmDefault),
      //libraryDependencies += "org.scalacheck" %% "scalacheck" % scalaCheck(scalaVersion.value)
      scalaCheckVersion := sys.props.getOrElse("akka.build.scalaCheckVersion", scalaCheck(scalaVersion.value)), // unmodified 3-clause BSD
      scalaTestVersion := ScalaTest,
      java8CompatVersion := java8Compat(scalaVersion.value))
  }

  def notOnScala211[T](scalaBinaryVersion: String, values: Seq[T]): Seq[T] = scalaBinaryVersion match {
    case "2.11" => Nil
    case _ => values
  }

  def notOnJdk8[T](values: Seq[T]): Seq[T] =
    if (isJdk1dot) Nil
    else values

  def isJdk1dot: Boolean = javaVersion.startsWith("1.")

  /** Returns true if the `scalaVersion` starts with `2` and its minor version
    * satisfies the given predicate `op` with provided `minorVersion` target.
    *
    * {{{ isScalaMinor(">=", 13, scalaVersion.value)) }}}
    *
    * @param op the predicate used to evaluate the minor version against: [ >, <, ==, >=, <= ]
    * @param minorVersion the minor version to test for
    * @param scalaVersion the actual SBT `scalaVersion.value`
    */
  def isScalaMinor(op: String, minorVersion: Long, scalaVersion: String): Boolean = {
    require(op != null, "'oper' must not be null.")
    require(op.nonEmpty, "'oper' must not be empty.")

    val eval = (f: Long => Boolean) =>
      CrossVersion.partialVersion(scalaVersion) match {
        case Some((2, n)) => f(n)
        case _            => false
      }

    op match {
      case o if o == ">"  => eval(_ > minorVersion)
      case o if o == "<"  => eval(_ < minorVersion)
      case o if o == "==" => eval(_ == minorVersion)
      case o if o == ">=" => eval(_ >= minorVersion)
      case o if o == "<=" => eval(_ <= minorVersion)
      case _ => throw new IllegalArgumentException(s"Unsupported operation $op for 'isScalaMinor'.")
    }
  }
}
