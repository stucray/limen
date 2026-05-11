# Slice branching workflow

How to work through a PRD's slices without stalling between them. The default
flow (push slice, wait for CI, click *Squash & merge*, wait for the squash
commit to land on `main`, branch the next slice off `main`) creates wall-clock
gaps where you have nothing to work on. This doc covers the two patterns that
remove those gaps.

## When each pattern applies

The right pattern depends on whether the next slice you want to start depends
on the slice currently in CI.

| Next slice is… | Pattern | Cost |
|---|---|---|
| Independent (different module, no import dependency) | Parallel branches off current `main` | None — just branch and go |
| Dependent (imports types, uses methods, builds on changes from current PR) | Local-stacked branch + rebase | One `git rebase` per stacked slice |

Slices inside a single tracer-bullet PRD are usually dependent: slice 1 lays
the spine, slice 2 thickens, slice 3 hardens. Treat dependent as the default
within a PRD; independent is for unrelated work pulled in alongside.

## Independent slices: parallel branches

For slices with no code-level dependency on the in-flight PR.

```sh
# Slice A's PR is up and in CI. Start slice B in parallel:
git fetch origin main
git checkout main
git pull
git checkout -b feat/slice-b
# ... work, commit, push, open PR with base=main ...
```

Both PRs run CI concurrently. Merge them in whichever order goes green first.
This is the cheapest pattern — no new mechanics, no waiting.

## Dependent slices: local-stacked branch + rebase

When slice N+1 needs code from slice N (new types, new methods, modified
behavior), you cannot branch off current `main` — it doesn't have slice N's
code yet. Instead, branch off slice N's local tip and rebase after slice N
merges.

### Sequence

```sh
# 1. Slice N is pushed and its PR is open & in CI.
#    Stack the next slice locally — do NOT push yet.
git checkout feat/slice-n
git checkout -b feat/slice-n-plus-1

# ... work on slice N+1, commit freely ...

# 2. Slice N's PR is squash-merged to main with --delete-branch.
#    Bring slice N+1 onto the new main.
git fetch origin main
git checkout feat/slice-n-plus-1
git rebase origin/main

# 3. Push and open slice N+1's PR with base=main.
git push -u origin feat/slice-n-plus-1
gh pr create --base main
```

The rebase in step 2 drops slice N's commits from your branch (they're now in
`main` via the squash commit) and replays only slice N+1's own commits.
Git's patch-equivalence detection handles this automatically.

### Repeating for slice N+2

While slice N+1 is in CI, you can stack slice N+2 off slice N+1's tip the
same way. The chain compounds: at any moment you have one PR open and one or
more local branches stacked behind it.

## The amendment gotcha

If reviewer feedback forces you to push amendments to slice N (whether by
adding commits or by force-pushing a rewritten history), the naive
`git rebase origin/main` on slice N+1 stops working — patch-equivalence
detection fails when slice N's commit hashes change.

Recovery: rebase **only slice N+1's own commits** onto the new `main`:

```sh
# Find slice N+1's first commit (the one immediately after slice N's tip)
git log --oneline feat/slice-n-plus-1

# Suppose slice N's old tip was abc1234, and slice N+1's first commit is def5678.
# Replay def5678..HEAD onto origin/main:
git rebase --onto origin/main abc1234 feat/slice-n-plus-1
```

`git rebase --onto <new-base> <old-base> <branch>` is the explicit form: take
the commits between `<old-base>` and `<branch>`'s tip, and replay them on top
of `<new-base>`. The naive `git rebase origin/main` is equivalent to
`--onto origin/main HEAD~N feat/slice-n-plus-1`, where `HEAD~N` is wherever
git *guesses* the fork point was — and the guess is wrong once the parent
history was rewritten.

## What NOT to do

**Don't open the dependent PR with `base=feat/slice-n`.** Two pathologies
hit at once:

1. The CI workflow filters on `branches: [main]` in `pull_request:` triggers
   (`.github/workflows/ci.yml`). A PR with `base=feat/slice-n` gets **no CI
   runs** until retargeted.
2. `gh pr merge feat/slice-n --squash --delete-branch` deletes the base ref,
   which causes GitHub to **auto-close** any open PR pointing at it. Once
   auto-closed, `gh pr edit --base` and `gh pr reopen` both refuse.

If you've already opened the dependent PR against `feat/slice-n` for early
review, retarget it to `main` (`gh pr edit <dependent> --base main`) **before**
merging the predecessor. Otherwise: recovery means a local rebase, a
force-push, and filing a replacement PR.

The local-stacked-branch technique above sidesteps this entirely — the stack
is local only; the dependent slice's PR is never opened with a feature-branch
base.

## Quick decision flow

```
Next slice ready to start while current PR is in CI?
├── Does it depend on the current PR's code?
│   ├── No  → branch off current main, push, open PR. Done.
│   └── Yes → branch off current PR's local tip, work locally.
│              When current PR merges:
│              ├── Was current PR amended during review?
│              │   ├── No  → git rebase origin/main
│              │   └── Yes → git rebase --onto origin/main <old-tip> <branch>
│              Then push, open PR with base=main.
```
