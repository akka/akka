import akka._
import com.typesafe.sbt.pgp.PgpKeys.publishSigned

enablePlugins(JmhPlugin, ScaladocNoVerificationOfDiagrams)
disablePlugins(Unidoc, MimaPlugin)

AkkaBuild.defaultSettings
Formatting.formatSettings

AkkaBuild.dontPublishSettings
AkkaBuild.dontPublishDocsSettings
Dependencies.benchJmh
