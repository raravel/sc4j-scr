# Upstream

The sources under `src/main` come from SC:R Enhanced (https://github.com/fogofwarlabs/sc4j-scr-enhanced, Apache-2.0), branch `develop`,
commit `f2424f3f9f2ec24c1627c2d8e5dd8fc8004016f5` (version `0.1.4-SNAPSHOT`), built there against sc4j-scr `v0.1.10`, the version this fork
merged.

Only the classes the script runtime (`ScriptSimulationPlugin`), the script check (`ScriptCheckUtils`) and the project files
(`ScriptProjectUtils`) need are copied, with the prelude scripts and the two declaration files. Their package names stay
`org.fogofwarlabs.sc4j.screnhanced`, so every file diffs against its upstream file.

Not copied: the service registrations under `META-INF/services` (eud-runner attaches the runtime itself, so a run without a script folder
plays exactly as before), the large-map, Beacon.Link transfer and classic memory extensions, the script tool (`init`, `run`, `pack`, ...),
the clone tool and the overlay.

Changes made here:

- `script/ScriptSimulationPlugin.java`: a second constructor that takes a `ScriptLogOutput`, so eud-runner puts the script's log messages
  and errors in its report.
- `script/ScriptSimulationPlugin.java`: that constructor also takes the host's globals and hands them to the runtime.
- `script/runtime/ScriptRuntime.java`: a `create` overload whose `BiConsumer<Context, Scriptable>` adds the host's globals to the global
  scope after the game API and before the prelude (eud-runner's map test host), and `callShared` with its `SharedCall` result, which calls a
  script function as shared code between frames within one instruction budget (the steps of a map test).
