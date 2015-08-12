import akka._
import com.typesafe.sbt.pgp.PgpKeys.publishSigned

enablePlugins(JmhPlugin, ScaladocNoVerificationOfDiagrams)
disablePlugins(Unidoc)

AkkaBuild.defaultSettings

AkkaBuild.dontPublishSettings

Dependencies.benchJmh
