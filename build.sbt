lazy val `parallel-covariant` = project

lazy val continuation = project.dependsOn(`parallel-covariant`)

lazy val `transformers-tryt-covariant` = project.dependsOn(`parallel-covariant`)

lazy val task = project.dependsOn(continuation, `transformers-tryt-covariant`)

lazy val `transformers-resourcet-covariant` = project.dependsOn(`parallel-covariant`)

lazy val raii = project.dependsOn(`transformers-resourcet-covariant`, task)
