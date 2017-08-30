lazy val `parallel-covariant` = project

lazy val continuation = project.dependsOn(`parallel-covariant`)

lazy val `tryt-covariant` = project.dependsOn(`parallel-covariant`)

lazy val task = project.dependsOn(continuation, `tryt-covariant`)

lazy val `resourcet-covariant` = project.dependsOn(`parallel-covariant`)

lazy val raii = project.dependsOn(`resourcet-covariant`, task)
