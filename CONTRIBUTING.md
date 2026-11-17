# Contributing

Use Java 25 and Maven 3.9+. `scripts/build.sh` builds a copied public SDK and verifies
this independent adapter from the exact public commit in `sdk-revision.txt`. It
never builds another task's source directory. For coordinated development, supply
the reviewed `GIVOPS_SDK_SOURCE`, or a reviewed archive plus
`GIVOPS_SDK_ARCHIVE_SHA256`. A source checkout must be frozen while copied.
`GIVOPS_MAVEN_REPOSITORY` chooses an isolated cache; the default is `.m2/` here.
No private core, private fixtures, credentials, database or Backlog runtime is needed.

After the SDK is installed in that cache, run `mvn -B -Dmaven.repo.local=.m2 clean verify`.
Tests use synthetic immutable tree/blob hosts, including denied/stale access,
malformed files, namespace collisions, Markdown assets and bounded streams. Add
focused behavior tests, not implementation mirrors. Git histories used for host
integration belong in `givops/test-fixtures`; public unit fixtures stay self-contained.

Keep every Backlog format/identity/status rule in this adapter. Never import private
host classes or expose filesystem paths or source bytes in logs. Do not run authoring
callbacks. Do not add writable capabilities to the read-only provider. Document
format and SDK bounds explicitly. Run `git diff --check` and inspect JAR metadata,
Boot imports and runtime dependencies before handoff. Publication needs separate
authorization; local implementation does not reserve or create a remote repository.
