import akka.{ AkkaBuild, Dependencies, Formatting, ScaladocNoVerificationOfDiagrams, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

AkkaBuild.experimentalSettings

Formatting.formatSettings

OSGi.persistenceQuery

Dependencies.persistenceQuery

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-persistence-query-experimental").value

enablePlugins(ScaladocNoVerificationOfDiagrams)

fork in Test := true
