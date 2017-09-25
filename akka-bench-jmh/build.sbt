import akka._
import com.typesafe.sbt.pgp.PgpKeys.publishSigned

enablePlugins(JmhPlugin, ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)

AkkaBuild.defaultSettings

AkkaBuild.dontPublishSettings
AkkaBuild.dontPublishDocsSettings
Dependencies.benchJmh
