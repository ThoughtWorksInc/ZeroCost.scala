libraryDependencies += "org.typelevel" %% "cats-core" % "1.0.0-MF"

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.4")

scalacOptions += "-Ypartial-unification"

libraryDependencies += "org.scalamock" %% "scalamock-scalatest-support" % "3.6.0" % Test

enablePlugins(Example)

exampleSuperTypes += "_root_.org.scalamock.scalatest.MockFactory"
