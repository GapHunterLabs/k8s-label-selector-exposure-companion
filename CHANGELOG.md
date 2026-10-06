<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# K8s Label-Selector Exposure Companion Changelog

## [Unreleased]

### Added

- A description page for the inspection in **Settings | Editor |
  Inspections**, which showed "Under construction".

### Changed

- The rating prompt's local counter keeps one-way fingerprints of findings
  instead of their file paths, and deletes the list that earlier versions
  kept.
- `PRIVACY.md` describes the values the plugin keeps in the IDE's local
  settings.

## [0.1.1]

### Fixed

- Review/star CTA now links to this plugin's own Marketplace
  reviews page instead of the vendor's generic plugin list.

## [0.1.0]

### Added

- Whole-project label-selector resolution index (real Kubernetes
  set-based semantics: `matchLabels` + `matchExpressions`) across every
  YAML manifest, flagging a Service-exposed workload left uncovered by
  every NetworkPolicy's Ingress podSelector.

[Unreleased]: https://github.com/GapHunterLabs/k8s-label-selector-exposure-companion/compare/0.1.1...HEAD
[0.1.1]: https://github.com/GapHunterLabs/k8s-label-selector-exposure-companion/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/GapHunterLabs/k8s-label-selector-exposure-companion/commits/0.1.0
