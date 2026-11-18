# GivOps Backlog.md adapter

Independent, read-only Java provider for committed Backlog.md v1.55.3 files. It
implements lightweight ticket observations, metadata browsing, full Markdown and
revision-pinned asset reads through public GivOps SDK contracts. No Backlog CLI,
server, Node, Bun, private core source or database is needed to build/test it.

## Build and install

Java 25, Maven 3.9+, Spring Boot 4.0.9, exact SDK API **0.5** and SDK
artifacts `0.5.0-SNAPSHOT` are required. `sdk-revision.txt` pins merged public
SDK API 0.5 commit `8226eab4967b8482327ad3cbc4b20c4e92a33ed1`.
The bootstrap builds that exact public source. This source pin does not imply a
tagged SDK release; the adapter artifact remains `0.1.0-SNAPSHOT`.
Hosts with earlier SDK API levels are incompatible.

```sh
scripts/build.sh
```

For coordinated development, override the source explicitly:

```sh
GIVOPS_SDK_SOURCE=/absolute/path/to/reviewed-sdk scripts/build.sh
```

The default script clones the public SDK at its pin and uses an isolated Maven
cache. An explicit source override is copied before building. Alternatively set
`GIVOPS_SDK_ARCHIVE` and its required `GIVOPS_SDK_ARCHIVE_SHA256`. See
[CONTRIBUTING.md](CONTRIBUTING.md). Build output is `target/adapter-backlog-0.1.0-SNAPSHOT.jar`.
Artifact version and SDK API version are distinct.

Install the plain library JAR into the compatible host adapter directory, normally
`/opt/givops/adapters`, and restart. Also provide required non-host dependencies
(SnakeYAML and commonmark); never bundle a second SDK or Spring version. Inspect
`mvn dependency:tree` against the actual host distribution before installing.
A read-only bind mount replaces the entire directory; retain other installed
providers. Hosts must match SDK API 0.5 exactly; earlier API levels must reject
this artifact. Compatible Backlog fixes can replace this JAR independently.
There is no hosted Backlog service to configure and no fallback embedded provider.

## Source configuration

Provider `BACKLOG_MD`, artifact ID `io.givops:adapter-backlog`, capabilities
`ticketing.read`, `ticketing.browse`, `ticketing.content`, `ticketing.assets`.
The host binds a registered content-capable repository, immutable configuration
revision, ref such as `refs/heads/main`, store root, configuration path and approved
asset roots. `HISTORY_ONLY` repositories cannot supply content. This adapter never
fetches, checks out files, expands a history-only mirror or manages credentials.
Host authorization applies even for a local or anonymous source.

Example adapter settings (non-secret):

```json
{
  "storeId": "givops-docs",
  "identityPolicy": "no-reuse",
  "workflow": "givops",
  "includeDrafts": "false",
  "format": "backlog-md-1.55.3"
}
```

For migrated GivOps tasks use root `backlog`, configuration `backlog/config.yml`,
approved asset root `backlog/assets`, external project ID `givops-docs` (equal to
`storeId`) and project key `TASK` (equal to the configured task prefix). Repository
identity and explicit store ID namespace native IDs; file paths/titles/ref changes
do not create new tickets. Preserve storeId when intentionally relocating a store.
Different stores need different IDs. Migration ledgers/aliases are supplemental,
never required parser inputs. Retain allocated IDs forever: arbitrary unseen
permanent deletion/recreation cannot be detected from snapshots and is unsupported.

Managed GivOps stores use ordered statuses `cancelled`, `idea`, `todo`,
`in_progress`, `blocked`, `done`, default `todo`, and `includeDrafts: "false"`.

Choose an explicit workflow. `givops` maps idea/todo to new/open,
in_progress/blocked to indeterminate/in-progress, done to done/succeeded, and
cancelled to indeterminate/cancelled. Historical `draft` also maps to new/open
for read compatibility. Cancellation never means successful delivery.
`backlog` maps To Do / In Progress / Done. `custom` requires one `status.<native>`
setting per configured status with `category:OUTCOME`, e.g. `new:OPEN`,
`indeterminate:IN_PROGRESS`, `indeterminate:CANCELLED`, `done:SUCCEEDED`.
Explicit entries override a preset; every configured status must map. Settings do
not run upstream hooks, shell callbacks or automatic Git operations.

## Read behavior

The selected ref resolves once per operation. Configuration, all expected task
metadata, requested Markdown and assets come from that commit. A detail request
without a revision resolves the configured ref once for a fresh deep link or explicit
refresh and returns that snapshot revision; a pinned detail stays at its requested
commit. Full content and assets require an explicit immutable revision; missing
revisions never fall back to HEAD. Browse continuations bind the revision, query, access context and
project. Search is NFC-normalized case-insensitive ID/title substring; status,
category and labels filter exact values (OR within families, AND across families).
Sort is stable `key-asc` or `title-asc`; pages contain at most 100 entries.

Active `tasks/`, `archive/tasks/` and legacy `completed/` form one complete identity
index. Archives preserve raw status, remain directly readable and are an explicit
browse scope. Duplicate/case/zero-padding aliases fail the operation. Dotted decimal
segments normalize without numeric overflow. Legacy textual IDs retain their entire
identity; malformed tokens never truncate to a parent ID. Bare numbers are not
references. Missing config, malformed expected records, unsupported layout or
exceeded budgets fail rather than silently publish a partial inventory.

Optional drafts use the separate filename-bound DRAFT namespace and browse scope;
archived drafts are included in the archived scope when enabled. Ordinary task
status `draft` remains an active task. Draft promotion changes native identity;
the adapter does not infer continuity or include drafts in observation references.
Backlog.md 1.55.3 native browser Save can demote a TASK to DRAFT when its status
is `draft`. Managed GivOps stores therefore use `idea` for ordinary tasks and
exclude the reserved separate draft lifecycle. The `givops` preset includes `idea`;
older adapter builds can use explicit `status.idea=new:OPEN`. This reader does not
rename statuses or change source identities.

Observations contain only bounded metadata and the source revision. Description
is always null. The host performs atomic publication and locked access revalidation.
On-demand content returns the original complete Markdown, including front matter
and all narrative sections, so raw date precision/unknown metadata is preserved.
Date-only/timezone-less values are not invented Instants in metadata. No body,
comment or asset byte is persisted by the adapter; it owns no database or shared
content cache. Missing account mapping, remote writes, release membership and
Personal Tasks are unsupported. Requested release mapping returns UNSUPPORTED.

## Assets and limits

Relative Markdown links resolve from the ticket path; `/assets/` maps to the store's
assets folder. Only files beneath default or explicitly approved asset roots can
become opaque authenticated handles. CommonMark inline/reference links are parsed;
raw HTML is left for host sanitization. Safe external links are navigation only;
external images are never server-fetched. Missing/unsafe relative references have
no available asset handle; the renderer should show an unavailable link/image.

No symlinks, submodules, LFS hydration, working-copy files, filesystem paths, URL
fetching or scripts. Asset digests are SHA-256 of bytes from the same pinned snapshot;
handles bind the ticket, source and access context. Host rendering/download policy
must sanitize Markdown, validate media and serve active formats as downloads.
Opening an asset revalidates its manifest and access; revocation also stops streaming.
Always close returned streams/assets.

Markdown traversal allows at most 256 nesting levels and 100,000 nodes.

Bounds: 10,000 tree entries, 5,000 task files and 32 MiB task bytes per scan;
64 KiB configuration and 1 MiB per Markdown blob; 100 combined asset/link references;
10 MiB per asset. Parser/asset helper bounds may reject a document earlier. Complete
metadata scans are required to detect collisions, including unrequested tasks.
Bodies are dropped immediately except the explicitly requested document. No full-body
search, automatic content caching, distributed identity incarnation tracking or
arbitrary provider-version compatibility is claimed.

## Compatibility and ownership

Format baseline: [Backlog.md v1.55.3](https://github.com/MrLesk/Backlog.md/tree/0cf4fdbc70020327f4366a29d3f405f83d180301).
This is an independently implemented strict reader of that profile, not an upstream
format certification. Unsupported YAML constructs and irregular files are diagnosed;
no read repairs or rewrites source files. Public dependency licenses apply to their
own artifacts; no upstream implementation source is bundled here.

Task 67 owns generic SDK/core/browser contracts, access controls and metadata-only
persistence. Task 65 owns real task migration. Task 66 owns this adapter. No Backlog
parser, status vocabulary or filesystem layout belongs in core/frontend.
