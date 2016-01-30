import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings
OSGi.httpJackson
Dependencies.httpJackson
MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-http-jackson").value

enablePlugins(ScaladocNoVerificationOfDiagrams)
