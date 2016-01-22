import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings
OSGi.httpXml
Dependencies.httpXml
MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-http-xml").value
