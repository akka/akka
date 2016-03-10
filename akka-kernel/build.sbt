import akka.{ AkkaBuild, Dependencies, Formatting, ScaladocNoVerificationOfDiagrams }

AkkaBuild.defaultSettings
Formatting.formatSettings
Dependencies.kernel

enablePlugins(ScaladocNoVerificationOfDiagrams)
