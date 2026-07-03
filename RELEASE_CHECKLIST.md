## Tacita Release Checklist

(we should be able to automate most of this eventually)

### Cut new Release Branch

1. Ensure main branch is green
2. `git checkout -b release/v<VERSION>`
3. Push/track empty branch

### Version bump PRs

- Create 2 PRs to bump version
    - `[VERSION] Snapshot v<version>` points at `main`
    - `[VERSION] Release v<version>` points at new release branch
    - Update version in files:
        - `self.versions.toml`
        - `docs/CHANGELOG.md`

### Harden Release Branch

- Fix any bugs on the `main` branch first then cherry-pick (via PR) into release branch

### Release

1. From the release branch, run `./scripts/ship-release.py --output <report.json>`
2. This creates the GitHub release + `v<VERSION>` tag (pointing to the release branch)
3. The tag push triggers the publish-artifacts workflow (Sonatype Central upload + site deploy)

### Hotfixes

- We do not cut new release branches for hotfixes, instead we append to the effected release branch and add a new
  release tag
- All fixes (including hotfixes) should be applied to the `main` branch first whenever possible and cherry-picked onto
  the appropriate release-branch for a hotfix.
