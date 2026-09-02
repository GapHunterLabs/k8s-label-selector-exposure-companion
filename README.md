# K8s Label-Selector Exposure Companion

Flags a workload that's Service-exposed but silently uncovered by
every NetworkPolicy in the project.

## Why it exists

"Namespace-selector-based policies can degrade silently every time a
new namespace is created without the expected label", and "a pod not
selected by any NetworkPolicy is not governed by any policy" -- both
real, documented Kubernetes failure patterns (OWASP Kubernetes Top 10,
inadequate network segmentation; CWE-284). kube-score and Checkov are
CLI tools; this catalog's own `k8s-resource-limit-companion`/
`k8s-readiness-liveness-probe-companion` scan a single manifest with no
selector resolution at all. No dedicated Marketplace plugin found for
this exact angle.

## Why built this way

- **Real Kubernetes set-based selector semantics** -- `matchLabels`
  (AND equality) plus `matchExpressions` (`In`/`NotIn`/`Exists`), never
  a substring/text match. An empty selector correctly matches
  everything, exactly as the platform defines it.
- **A whole-project index**, not a single manifest: a Deployment's pod
  template, a Service's selector, and a NetworkPolicy's podSelector are
  almost always in three different files -- resolving exposure requires
  cross-referencing all of them.
- **Not a real YAML parser** -- an indentation-based micro-scanner,
  same discipline as this catalog's other Kubernetes plugins, extended
  to walk nested key paths.
- **Cached per-project**, invalidated on any PSI change.

## v0.1 scope — stated honestly, not exhaustively

- Treats the whole project as one flat, implicit namespace -- an
  explicit `metadata.namespace:` is never cross-checked (the safe
  direction: this can only make the tool miss a real gap, never
  over-flag one).
- Only `matchLabels` + `In`/`NotIn`/`Exists` `matchExpressions` --
  `DoesNotExist` is treated as satisfied rather than guessed.
- One `kind:` per manifest file (a multi-doc `---` file isn't specially
  split).
- A project with more than 500 YAML files skips whole-project analysis
  entirely rather than risk hanging the IDE.

## Usage

Open a Deployment/Pod/StatefulSet/DaemonSet manifest in a project that
also has at least one NetworkPolicy and one Service. If this workload
is Service-exposed but no NetworkPolicy's Ingress podSelector matches
its pod-template labels, its `kind:` line shows a warning.

## Enterprise / Team Licensing

Need enterprise features, custom rules, or team licensing? Contact us at
**gaphunterlabs@gmail.com**.

## Development

```
./gradlew test           # unit tests
./gradlew buildPlugin    # generates build/distributions/*.zip
./gradlew verifyPlugin   # checks compatibility against real IDEs
```

## License

Apache-2.0. See `LICENSE`.
