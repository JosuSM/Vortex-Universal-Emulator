# GitHub Environments

This project uses three GitHub Actions environments for Google Play releases.

## Recommended Environments

### play-internal
- Purpose: internal validation builds only.
- Required reviewers: none.
- Wait timer: 0 minutes.
- Deployment branches/tags: default branch only.
- Self-review prevention: off.
- Recommended secrets location: repository-level secrets are enough.

### play-beta
- Purpose: controlled tester rollout after internal validation.
- Required reviewers: 1 reviewer.
- Wait timer: 5 minutes.
- Deployment branches/tags: default branch and release tags.
- Self-review prevention: on.
- Recommended reviewers: you plus one trusted release approver.

### play-production
- Purpose: public Google Play releases.
- Required reviewers: 2 reviewers if available, otherwise 1.
- Wait timer: 30 minutes.
- Deployment branches/tags: signed release tags only, for example `play-v*`.
- Self-review prevention: on.
- Recommended reviewers: release owner and backup approver.

## Setup Steps

1. Create the environments `play-internal`, `play-beta`, and `play-production` in the repository settings.
2. Add the protection rules above to each environment.
3. Keep signing secrets at repository scope unless you want different credentials per environment.
4. If you want stricter production isolation, move `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` into the `play-production` environment instead of repository secrets.
5. Run the workflow manually with `action=upload` to `internal` first.
6. After validation, run the workflow again with `action=promote` from `internal` to `beta` or `production`.

## Reviewer Model

Recommended reviewer assignment:
- `play-internal`: no reviewer.
- `play-beta`: primary maintainer.
- `play-production`: primary maintainer plus backup approver.

If you are the only maintainer, keep `play-beta` at 1 reviewer and `play-production` at 1 reviewer with self-review prevention off until you add a second approver.
