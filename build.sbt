lazy val LiftIO = project

lazy val parallel = project

lazy val continuation = project.dependsOn(parallel, LiftIO)

lazy val tryt = project.dependsOn(parallel, LiftIO)

lazy val task = project.dependsOn(continuation, tryt, MultipleException)

lazy val resourcet = project.dependsOn(parallel, LiftIO)

lazy val raii = project.dependsOn(resourcet, parallel, task)

lazy val MultipleException = project

lazy val FlatMappable = project

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
