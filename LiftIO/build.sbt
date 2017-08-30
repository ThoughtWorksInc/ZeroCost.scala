libraryDependencies += "org.typelevel" %% "cats-core" % "1.0.0-MF"

libraryDependencies += "com.github.mpilquist" %% "simulacrum" % "0.10.0" % Provided

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.patch)
