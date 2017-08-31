addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.4")

scalacOptions += "-Ypartial-unification"

enablePlugins(Example)

exampleSuperTypes ~= { oldExampleSuperTypes =>
  import oldExampleSuperTypes._
  updated(indexOf("_root_.org.scalatest.FreeSpec"), "_root_.org.scalatest.AsyncFreeSpec")
}

exampleSuperTypes += "_root_.org.scalatest.Inside"
