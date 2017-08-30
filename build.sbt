lazy val LiftIO = project

lazy val `parallel-covariant` = project

lazy val continuation = project.dependsOn(`parallel-covariant`)

lazy val `tryt-covariant` = project.dependsOn(`parallel-covariant`, LiftIO)

lazy val task = project.dependsOn(continuation, `tryt-covariant`)

lazy val `resourcet-covariant` = project.dependsOn(`parallel-covariant`, LiftIO)

lazy val raii = project.dependsOn(`resourcet-covariant`, task)

crossScalaVersions in ThisBuild := Seq("2.11.11", "2.12.3")
