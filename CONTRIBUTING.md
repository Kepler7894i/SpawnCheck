# Contributing

## Supporting several Minecraft versions: branches and merging forward

Spawn Check targets one Minecraft version per branch:

| Branch | Targets | Releases |
| --- | --- | --- |
| `main` | the **newest** Minecraft version | the release tagged with that version |
| `supported/<version>` (e.g. `supported/26.2`) | an **older** Minecraft version that is still maintained | the release tagged `<version>` (e.g. `26.2`) |

The target of each branch is just `minecraftVersion` in `gradle.properties`. Every push to `main` or to a `supported/<version>` branch runs the release workflow, which rebuilds that branch and replaces the release whose tag is its Minecraft version. Branches therefore never overwrite each other's releases.

### The rule: features go on the oldest branch, then merge forward

**Put every change that isn't specific to a Minecraft version (features, bug fixes, docs) on the oldest maintained branch, then merge that branch into the next newer one, and so on up to `main`.** Never merge the other way (newer into older), and don't copy changes by hand.

```
supported/26.2 ──●──●──●───────●──────●
           \      \    /        \
main        ●──●───●──M──●───────M     (M = merge of supported/26.2; the "port to 26.3" commit lives only here)
```

**Why:**

- **The newer branch only contains extra commits.** Everything that is specific to the new Minecraft version (API renames, new `minecraftVersion` and loader versions) is committed on the newer branch alone. Merging forward carries the shared work up without ever carrying a port back down, so older branches never get code that breaks their build.
- **Git remembers what was merged.** A merge records that the commits are already in the newer branch, so the next merge only brings the new ones. Cherry-picking copies the change but not that knowledge, so the same change reappears as a conflict later.
- **Fixes land once.** There is no "did I also fix it on 26.2?" question: if it is on the oldest branch and merged forward, it is everywhere.

For this to work, **keep version-specific changes in their own commits**, separate from feature work. A port commit should only change what the new Minecraft version forced (usually `gradle.properties`, a few API calls and the re-rendered README).

### How to do it

Adding a feature or fix (say `supported/26.2` is the oldest and `main` is 26.3):

```bash
git checkout supported/26.2
git checkout -b my-feature          # or work directly on supported/26.2
# ...change, build with ./gradlew build, commit...
git checkout supported/26.2 && git merge my-feature
git push origin supported/26.2             # publishes the 26.2 release

git checkout main
git merge supported/26.2                   # bring it forward
./gradlew build                     # the new version may need a small fix: commit it on main
git push origin main                # publishes the 26.3 release
```

If there are several maintained versions, merge through them in order (`supported/26.2` → `supported/26.3` → `main`).

**Conflicts.** They usually only happen in lines the port touched. Keep the **newer** branch's version of those lines (for example its `minecraftVersion`, `neoforgeVersion` and any renamed API call) and the shared change from the older one. Don't resolve a conflict by taking the old version of a port line: that breaks the newer build.

**Version numbers.** `version` (the mod's own version) is shared. Bump it on the oldest branch together with the change so merging brings the bump along. `minecraftVersion`, `fabricLoaderVersion`, `fabricApiVersion` and `neoforgeVersion` belong to each branch and must not be overwritten by a merge; if git shows a conflict there, keep the newer branch's values.

### Why the `supported/` prefix

Version branches are named `supported/<version>` and never just `<version>`, because the release tag for a version is `<version>`. A branch and a tag with the same name make git warn `refname '26.2' is ambiguous`, and some commands then pick the **tag**: `git merge 26.2` silently reports "Already up to date" because it merged the tag, which is already in `main`. The prefix keeps the two names apart. If you ever see that warning, a branch is named without the prefix: rename it.

Check you are on the oldest branch (`git branch --show-current`) *before* you start editing: never commit a shared change on `main`, and never merge `main` (or any newer branch) into an older one.

### Starting support for a new Minecraft version

1. Create a maintenance branch from the current `main` for the version you are leaving: `git branch supported/26.3 main && git push origin supported/26.3`. (It keeps publishing its release when you push to it.)
2. On `main`, retarget: `java tools/SetVersion.java <new version>`, then `./gradlew build` and fix whatever broke, then `java tools/RenderReadme.java`.
3. Commit that as a **port** commit (don't mix in features) and push `main`.

### Dropping an old version

Stop merging into its branch and delete it (or leave it as a frozen branch). Its release stays available on the Releases page.

## Building and testing

`./gradlew build` builds both loaders. There are no automated tests, so before opening a pull request please start a dev server for each loader you touched (`./gradlew :fabric:runServer`, `:neoforge:runServer`) and, for anything client-side, a dev client (`:fabric:runClient`, `:neoforge:runClient`).

The loader-independent code lives in `common/`; `fabric/` and `neoforge/` should only contain thin glue to each loader's events.
