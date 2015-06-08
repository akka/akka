import akka.{ AkkaBuild, Dependencies, Formatting, ScaladocNoVerificationOfDiagrams, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

AkkaBuild.experimentalSettings

Formatting.formatSettings

OSGi.persistenceQuery

Dependencies.persistenceQuery

//MimaKeys.previousArtifact := akkaPreviousArtifact("akka-persistence-query-experimental").value

enablePlugins(ScaladocNoVerificationOfDiagrams)
