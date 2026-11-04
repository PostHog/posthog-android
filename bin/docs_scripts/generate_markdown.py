"""Generate the SDK reference Markdown from the KDoc comments with Dokka's GFM output.

Dokka (a build-time Gradle plugin, added by dokka-gfm.init.gradle and never published) renders one
Markdown file per symbol. This script groups those files and writes the docs site's front matter.

A `posthog-capability: <slug>` KDoc line puts a symbol under that PostHog/sdk-specs capability, in the
sdk-specs README's order. Untagged symbols go to SDK utils.

    python3 bin/docs_scripts/generate_markdown.py --sdk-specs DIR [--major 4 --experimental]

Set GRADLE_ARGS for extra Gradle arguments.
"""

import argparse
import functools
import html
import os
import re
import shlex
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "posthog/build/dokka/gfm/posthog"
ANDROID = ROOT / "posthog-android/build/dokka/gfm/posthog-android"
# Public classes users call: Dokka's Companion pages, in the order their entries are listed.
# The interfaces come first because only they show parameter defaults.
CLIENTS = [
    ("PostHogAndroid", [ANDROID / "com.posthog.android/-post-hog-android/-companion"]),
    (
        "PostHog",
        [CORE / f"com.posthog/-post-hog-{d}" for d in ("interface", "core-interface")]
        + [CORE / "com.posthog/-post-hog/-companion"],
    ),
]
# Public but not for app code: interfaces the clients implement, internals and integration plumbing.
HIDDEN = """PostHog PostHogCoreInterface PostHogInterface PostHogStateless PostHogStatelessInterface
PostHogInternal PostHogExperimental PostHogVisibleForTesting PostHogInternalReplayApi
PostHogReplayIntegration PostHogSurveysIntegration PostHogNativeCrashIntegration
PostHogDrawableConverter PostHogOptInReceiver PostHogAndroid overrideSharedInstance resetSharedInstance""".split()
# Members every class and enum has, which say nothing about the SDK.
NOISE = "equals hashCode toString copy valueOf values entries name ordinal Companion".split()
# Members of the public config classes that are @PostHogInternal, which Dokka can't tell.
NOISE += """cachePreferences context dateProvider legacyStoragePrefix logger logsStoragePrefix networkStatus
replayStoragePrefix sdkName sdkVersion serializer snapshotEndpoint storagePrefix""".split()
FIRST = ["setup", "capture", "identify"]
TAG = re.compile(r"^posthog-capability:[ \t]*([a-z0-9-]+)[ \t]*$\n?", re.M)
PROMPT = "[jvm]", "[androidJvm]"


def run_dokka() -> None:
    cmd = ["./gradlew", "--init-script", "bin/docs_scripts/dokka-gfm.init.gradle", "--no-daemon"]
    cmd += shlex.split(os.environ.get("GRADLE_ARGS", ""))
    subprocess.run([*cmd, ":posthog:dokkaGfm", ":posthog-android:dokkaGfm"], cwd=ROOT, check=True)


def clean(md: str) -> str:
    """Drops Dokka's breadcrumb, links and source set labels, so what is left reads as plain Markdown."""
    md = re.sub(r"^//.*\n", "", md)
    md = re.sub(r"\[([^\]]+)\]\((?:https?://|[^)\s]*\.md)[^)]*\)", r"\1", md)
    md = re.sub(r"^(?:jvm|androidJvm)\n\n", "", md, flags=re.M)
    # The site reads Markdown as MDX 1, so HTML must be JSX.
    return md.replace("<br>", "<br />")


def code(signature: str) -> str:
    signature = re.sub(r"^(?:(?:abstract|open|override)\s+)+", "", html.unescape(signature))
    return re.sub(r"@(\w+)constructor", r" @\1 constructor", signature).replace("~~", "")


def table_cell(cell: str) -> str:
    """A table cell holds `[jvm]<br>signature<br>description`, once per overload."""
    parts = []
    for overload in re.split(r"\[(?:jvm|androidJvm)\]<br />", cell):
        if overload.strip():
            sig, _, doc = overload.partition("<br />")
            parts.append(f"`{code(sig)}`" + (f"<br />{doc}" if doc else ""))
    return "<br />".join(parts) or cell


def tables(md: str) -> str:
    out = []
    for line in md.split("\n"):
        cells = line.split(" | ", 1)
        if line.startswith("| ") and len(cells) == 2 and re.match(r"\[(?:jvm|androidJvm)\]", cells[1]):
            if cells[0][2:] in NOISE:
                continue
            line = f"{cells[0]} | {table_cell(cells[1].removesuffix(' |'))} |"
        out.append(line)
    return "\n".join(out)


@functools.cache
def type_pages() -> dict[str, Path]:
    """Each public type's page, by name."""
    pages = {}
    for pkg in sorted([*CORE.glob("com.posthog*"), *ANDROID.glob("com.posthog.android*")]):
        if ".internal" not in pkg.name and ".vendor" not in pkg.name:
            for page in pkg.glob("-*/index.md"):
                pages[re.search(r"^# (.*)$", page.read_text(), re.M)[1]] = page
    return pages


def inherited(page: Path) -> str:
    """Matches the rows a type's page lists for members its supertypes declare. Dokka renders them bare."""
    sig = re.search(r"^# .*\n\n(?:\[(?:jvm|androidJvm)\]\\\n)?(.*)$", page.read_text(), re.M)
    parents = re.search(r"\) : (.*)$", re.sub(r"\]\([^)]*\)", "]", sig[1]))
    names = set()
    for parent in re.findall(r"\w+", parents[1]) if parents else []:
        if parent in type_pages():
            names |= set(re.findall(r"^\| \[(\w+)\]", type_pages()[parent].read_text(), re.M))
    return rf"^\| \[(?:{'|'.join(names) or '(?!)'})\]\(index\.md#.*\n"


def entry(path: Path, name: str) -> tuple[str, str | None]:
    """A symbol's page as one H3 entry, and its capability tag."""
    md = clean(re.sub(inherited(path), "", path.read_text(), flags=re.M)).lstrip()
    tag = TAG.search(md)
    md = TAG.sub("", md)
    # A class with annotations has no source set label before its signature.
    md = re.sub(r"^(# .*\n\n)(?!\[(?:jvm|androidJvm)\])", r"\1[jvm]\\\n", md, count=1, flags=re.M)
    md = re.sub(r"^# .*\n", f"### `{name}`\n", md, count=1, flags=re.M)
    # A signature follows its source set label, which is dropped. It goes in a code block.
    md = re.sub(r"^\[(?:jvm|androidJvm)\]\\\n(.*)$", lambda m: f"```kotlin\n{code(m[1])}\n```", md, flags=re.M)
    md = re.sub(r"^#{2,4} (?:Constructors|Inheritors)\n.*?(?=^#{2,4} |\Z)", "", md, flags=re.M | re.S)
    # Every other heading, Dokka's or one written in a KDoc, nests under the entry's H3.
    head, _, rest = md.partition("\n")
    in_fence, lines = False, []
    for line in rest.split("\n"):
        in_fence ^= bool(re.match(r"\s*```", line))
        lines.append(f"#### {line.lstrip('# ')}" if not in_fence and re.match(r"#{1,3} ", line) else line)
    md = "\n".join([head, *lines])
    md = re.sub(r"^#### \w+\n\n\| (?:Name \| Summary )?\|\n\|---\|(?:---\|)?\n+(?=#### |\Z)", "", tables(md), flags=re.M)
    return re.sub(r"\n{3,}", "\n\n", md).strip(), tag[1] if tag else None


def capabilities(checkout: str) -> dict[str, str]:
    """Each sdk-specs capability's slug and title in sentence case, in the README's order."""
    readme = (Path(checkout) / "README.md").read_text()
    rows = re.findall(r"^\| ([^|]+?) \| \[`openspec/specs/([a-z0-9-]+)/spec\.md`\]", readme, re.M)
    acronyms = {"id": "ID", "ai": "AI", "http": "HTTP", "mcp": "MCP"}
    titles = {s: " ".join(acronyms.get(w.lower(), w.lower()) for w in n.split()) for n, s in rows}
    # Setup, capture and identify come first; the rest follow the README.
    order = [*FIRST, *(s for s in titles if s not in FIRST)]
    return {s: titles[s][0].upper() + titles[s][1:] for s in order if s in titles}


def reference(caps: dict[str, str]) -> list[str]:
    tagged: dict[str, list[str]] = {}
    utils, clients = [], []
    for cls, folders in CLIENTS:
        mine = []
        pages = {p.name: p for f in reversed(folders) for p in f.glob("*.md") if p.name != "index.md"}
        for page in (pages[n] for n in sorted(pages)):
            name = re.search(r"^# (.*)$", page.read_text(), re.M)[1]
            if name in HIDDEN:
                continue
            md, tag = entry(page, f"{cls}.{name}")
            if tag and tag not in caps:
                raise SystemExit(f"{page}: unknown capability {tag}")
            (tagged.setdefault(tag, []) if tag else mine).append(md)
        (utils if cls == "PostHog" else clients).append((cls, mine))
    for page in sorted(ANDROID.glob("com.posthog.android/*.md")):
        if page.name != "index.md":  # Top-level functions, like the Compose modifier.
            utils[0][1].append(entry(page, re.search(r"^# (.*)$", page.read_text(), re.M)[1])[0])
    out = []
    for slug, title in caps.items():
        if slug in tagged:
            out += [f"## {title}", *tagged[slug]]
    out += ["## SDK utils", *utils[0][1]]
    for cls, mine in clients:
        out += [f"## {cls}", *mine]
    types = [entry(page, name)[0] for name, page in sorted(type_pages().items()) if name not in HIDDEN]
    return out + ["## Types", *types]


def front_matter(title: str, sections: list[str]) -> str:
    """The docs site's front matter. Its ToC lists the page's H2 sections only."""
    toc = []
    for heading in [s[3:] for s in sections if s.startswith("## ")]:
        anchor = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
        toc.append(f'  - value: "{heading}"\n    url: {anchor}\n    depth: 0')
    head = [f'title: "{title}"', "sidebar: Docs", "showTitle: true"]
    head += ["contentMaxWidthClass: max-w-full", "platformLogo: android", "tableOfContents:", *toc]
    return "\n".join(["---", *head, "---"])


def nest(markdown: str) -> str:
    """Moves each heading down one level and drops the H1. Code stays as written."""
    out, in_fence = [], False
    for line in markdown.strip().split("\n"):
        in_fence ^= bool(re.match(r"\s*```", line))
        if in_fence or not re.match(r"#{1,5} ", line):
            out.append(line)
        elif not line.startswith("# "):
            out.append("#" + line)
    return "\n".join(out)


def write(name: str, sections: list[str]) -> None:
    (ROOT / "references" / name).write_text("\n\n".join(sections) + "\n")
    print(f"wrote references/{name}")


def main() -> None:
    args = argparse.ArgumentParser(description="Generate the SDK reference Markdown.")
    args.add_argument("--sdk-specs", required=True, help="PostHog/sdk-specs checkout")
    args.add_argument("--major", type=int, help="target major, defaults to the declared one")
    args.add_argument("--experimental", action="store_true", help="the major isn't released yet")
    opts = args.parse_args()
    run_dokka()
    props = (ROOT / "gradle.properties").read_text()
    declared = int(re.search(r"^androidVersion=(\d+)", props, re.M)[1])
    major = opts.major or declared
    started = nest((ROOT / "docs/getting-started.md").read_text())
    body = ["## Getting started", started.strip(), *reference(capabilities(opts.sdk_specs))]
    title = f"PostHog Android SDK {major}.x" + (" (experimental)" if opts.experimental else "")
    write(f"posthog-android-{major}.md", [front_matter(title, body), *body])
    if not opts.experimental:
        write("posthog-android-reference.md", [front_matter("PostHog Android SDK", body), *body])
    if guides := sorted((ROOT / "docs").glob("migration-*.md"), reverse=True):
        head = "---\ntitle: PostHog Android SDK migration guides\nsidebar: Docs\nshowTitle: true\n---"
        write("posthog-android-migration.md", [head, *(g.read_text().strip() for g in guides)])


if __name__ == "__main__":
    main()
