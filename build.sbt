lazy val LiftIO = project

lazy val `parallel-covariant` = project

lazy val continuation = project.dependsOn(`parallel-covariant`)

lazy val `tryt-covariant` = project.dependsOn(`parallel-covariant`, LiftIO)

lazy val task = project.dependsOn(continuation, `tryt-covariant`)

lazy val `resourcet-covariant` = project.dependsOn(`parallel-covariant`, LiftIO)

lazy val raii = project.dependsOn(`resourcet-covariant`, task)

lazy val MultipleException = project

crossScalaVersions in ThisBuild := Seq("2.11.11", "2.12.3")

organization in ThisBuild := "com.thoughtworks.zerocost"

publishArtifact := false

lazy val unidoc =
  project
    .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
    .settings(
      UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := inAggregates(LocalRootProject),
      addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
      addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.patch),
      scalacOptions += "-Ypartial-unification"
    )