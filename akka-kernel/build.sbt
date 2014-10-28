import akka.{ AkkaBuild, Dependencies, Formatting, Unidoc }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettingsNoVerificationOfDiagrams

Unidoc.javadocSettings

libraryDependencies ++= Dependencies.kernel

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-kernel").value
