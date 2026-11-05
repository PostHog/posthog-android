"""Generate the SDK reference Markdown from the KDoc comments with Dokka.

Dokka (already applied to every module) writes one HTML page per symbol. BeautifulSoup reads each
page's signature, parameters and description, and markdownify turns the description into Markdown.
Our code only groups symbols by the `posthog-capability: <slug>` line in their KDoc, in the
PostHog/sdk-specs README's order, and writes front matter. Untagged symbols go to SDK utils.

    pip install -r bin/docs_scripts/requirements.txt
    python3 bin/docs_scripts/generate_markdown.py --sdk-specs DIR [--major 4 --experimental]

Set GRADLE_ARGS for extra Gradle arguments.
"""

import argparse
import functools
import os
import shlex
import subprocess
from pathlib import Path

from bs4 import BeautifulSoup
from markdownify import markdownify

ROOT = Path(__file__).resolve().parents[2]
MODULES = ["posthog", "posthog-android"]
FIRST = ["setup", "capture", "identify"]
TAG = "posthog-capability:"
# Public classes users call, as Dokka folders. The interfaces come first because only they show defaults.
CLIENTS = [
    ("PostHogAndroid", ["posthog-android/com.posthog.android/-post-hog-android/-companion"]),
    ("PostHog", [f"posthog/com.posthog/-post-hog-{d}" for d in ("interface", "core-interface")] + ["posthog/com.posthog/-post-hog/-companion"]),
]
# Public but not for app code: interfaces the clients implement, internals and integration plumbing.
HIDDEN = """PostHog PostHogCoreInterface PostHogInterface PostHogStateless PostHogStatelessInterface
PostHogInternal PostHogExperimental PostHogVisibleForTesting PostHogInternalReplayApi
PostHogReplayIntegration PostHogSurveysIntegration PostHogNativeCrashIntegration
PostHogDrawableConverter PostHogOptInReceiver PostHogAndroid overrideSharedInstance resetSharedInstance""".split()
# Members every class and enum has, and @PostHogInternal members of the config classes, which Dokka can't tell.
NOISE = set("""equals hashCode toString copy valueOf values entries name ordinal Companion cachePreferences
context dateProvider legacyStoragePrefix logger logsStoragePrefix networkStatus replayStoragePrefix sdkName
sdkVersion serializer snapshotEndpoint storagePrefix""".split())
MDX = str.maketrans({"{": "&#123;", "}": "&#125;"})  # MDX 1 reads braces as JSX


def run_dokka() -> None:
    cmd = ["./gradlew", "--no-daemon", *shlex.split(os.environ.get("GRADLE_ARGS", ""))]
    subprocess.run([*cmd, *(f":{m}:dokkaHtml" for m in MODULES)], cwd=ROOT, check=True)


@functools.cache
def html_root(module: str) -> Path:
    """Dokka's HTML for a module. The publishing config moves some modules' output."""
    return next(p.parent / module for p in (ROOT / module / "build").rglob("navigation.html"))


def folder(path: str) -> Path:
    module, rest = path.split("/", 1)
    return html_root(module) / rest


def content(page: Path):
    return BeautifulSoup(page.read_text(), "html.parser").select_one("#content")


def title(page: Path) -> str:
    return content(page).select_one("h1").get_text().strip()


def signature(node) -> str:
    text = node.select_one(".symbol").get_text().strip()
    for word in ("abstract ", "open ", "override "):
        text = text.removeprefix(word)
    return text


def lists(node) -> None:
    """Dokka's parameter and exception tables become `name: description` lists."""
    for table in node.select(".table"):
        rows = [r.select(".main-subrow > div") for r in table.select(".table-row")]
        items = "".join(f"<li><code>{k.get_text().strip()}</code>: {(v.p or v).decode_contents()}</li>" for k, v in rows)
        table.replace_with(BeautifulSoup(f"<ul>{items}</ul>", "html.parser"))


def markdown(node, level: int) -> str:
    lists(node)
    for h in node.find_all(["h2", "h3", "h4"]):
        h.name = f"h{min(level, 6)}"
    for text in node.find_all(string=True):  # braces in prose only; code keeps them
        if not text.find_parent(["code", "pre"]):
            text.replace_with(str(text).translate(MDX))
    md = markdownify(str(node), strip=["a"], heading_style="ATX", bullets="-")
    lines = [l for l in md.split("\n") if not l.strip().startswith(TAG)]
    return "\n".join(lines).strip().replace("<br>", "<br />")


def tag(node) -> str | None:
    for p in node.select("p"):
        if p.get_text().strip().startswith(TAG):
            return p.get_text().strip()[len(TAG) :].strip()
    return None


def entry(page: Path, label: str) -> tuple[str, str | None]:
    """A member's page as one H3 entry, and its capability tag."""
    body = content(page).select_one(".content")
    sig, found = signature(body), tag(body)
    for s in body.select(".symbol"):
        s.decompose()
    return f"### `{label}`\n\n```kotlin\n{sig}\n```\n\n{markdown(body, 4)}".strip(), found


@functools.cache
def type_pages() -> dict[str, Path]:
    """Each public type's page, by name."""
    pages = {}
    for module in MODULES:
        for pkg in sorted(html_root(module).glob("com.posthog*")):
            if ".internal" not in pkg.name and ".vendor" not in pkg.name:
                pages.update({title(p): p for p in pkg.glob("-*/index.html")})
    return pages


def type_entry(page: Path, name: str) -> str:
    """A type's declaration, description and the members it declares itself, each with its brief."""
    node = content(page)
    cover = node.select_one(".cover .content") or node.select_one(".cover")
    parts = [f"### `{name}`", f"```kotlin\n{signature(cover)}\n```"]
    for s in cover.select(".symbol"):
        s.decompose()
    parts.append(markdown(cover, 4))
    for kind, heading in (("PROPERTY", "Properties"), ("FUNCTION", "Functions"), ("ENTRY", "Entries")):
        rows = []
        for row in node.select(f"[data-togglable={kind}] .table-row"):
            link = row.select_one("a")
            # A member a supertype declares has no page here, only an anchor on this one.
            if not link or link["href"].startswith("index.html") or link.get_text().replace("\u200b", "") in NOISE:
                continue
            brief = row.select_one(".brief")
            sig = signature(row) if row.select_one(".symbol") else link.get_text()
            rows.append(f"- `{sig}`" + (f": {markdown(brief, 5)}" if brief else ""))
        if rows:
            parts.append(f"#### {heading}\n\n" + "\n".join(rows))
    return "\n\n".join(p for p in parts if p)


def capabilities(checkout: str) -> dict[str, str]:
    """Each sdk-specs capability's slug and title in sentence case, in the README's order."""
    acronyms = {"id": "ID", "ai": "AI", "http": "HTTP", "mcp": "MCP"}
    titles = {}
    for line in (Path(checkout) / "README.md").read_text().split("\n"):
        cells = line.split("|")
        if len(cells) > 2 and "openspec/specs/" in cells[2]:
            slug = cells[2].split("openspec/specs/")[1].split("/")[0]
            titles[slug] = " ".join(acronyms.get(w.lower(), w.lower()) for w in cells[1].split())
    order = [*FIRST, *(s for s in titles if s not in FIRST)]
    return {s: titles[s][0].upper() + titles[s][1:] for s in order if s in titles}


def reference(caps: dict[str, str]) -> list[str]:
    tagged: dict[str, list[str]] = {}
    utils, clients = [], []
    for cls, folders in CLIENTS:
        mine = []
        pages = {p.name: p for f in reversed(folders) for p in folder(f).glob("*.html") if p.name != "index.html"}
        for page in (pages[n] for n in sorted(pages)):
            name = title(page)
            if name in HIDDEN:
                continue
            md, found = entry(page, f"{cls}.{name}")
            if found and found not in caps:
                raise SystemExit(f"{page}: unknown capability {found}")
            (tagged.setdefault(found, []) if found else mine).append(md)
        (utils if cls == "PostHog" else clients).append((cls, mine))
    for page in sorted(folder("posthog-android/com.posthog.android").glob("*.html")):
        if page.name != "index.html":  # Top-level functions, like the Compose modifier.
            utils[0][1].append(entry(page, title(page))[0])
    out = []
    for slug, heading in caps.items():
        if slug in tagged:
            out += [f"## {heading}", *tagged[slug]]
    out += ["## SDK utils", *utils[0][1]]
    for cls, mine in clients:
        out += [f"## {cls}", *mine]
    types = [type_entry(page, name) for name, page in sorted(type_pages().items()) if name not in HIDDEN]
    return out + ["## Types", *types]


def anchor(heading: str) -> str:
    """The github-slugger slug the docs site gives a heading."""
    return "".join(c for c in heading.lower() if c.isalnum() or c in "-_ ").replace(" ", "-")


def front_matter(title: str, sections: list[str]) -> str:
    """The docs site's front matter. Its ToC lists the page's H2 sections only."""
    toc = []
    for heading in [s[3:] for s in sections if s.startswith("## ")]:
        toc.append(f'  - value: "{heading}"\n    url: {anchor(heading)}\n    depth: 0')
    head = [f'title: "{title}"', "sidebar: Docs", "showTitle: true"]
    head += ["contentMaxWidthClass: max-w-full", "platformLogo: android", "tableOfContents:", *toc]
    return "\n".join(["---", *head, "---"])


def nest(markdown: str) -> str:
    """Moves each heading down one level and drops the H1. Code stays as written."""
    out, fence = [], False
    for line in markdown.strip().split("\n"):
        fence ^= line.strip().startswith("```")
        if fence or not line.startswith("#"):
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
    props = (ROOT / "gradle.properties").read_text().split("\n")
    declared = int(next(l for l in props if l.startswith("androidVersion=")).split("=")[1].split(".")[0])
    major = opts.major or declared
    body = ["## Getting started", nest((ROOT / "docs/getting-started.md").read_text()), *reference(capabilities(opts.sdk_specs))]
    title = f"PostHog Android SDK {major}.x" + (" (experimental)" if opts.experimental else "")
    (ROOT / "references").mkdir(exist_ok=True)
    write(f"posthog-android-{major}.md", [front_matter(title, body), *body])
    if not opts.experimental:
        write("posthog-android-reference.md", [front_matter("PostHog Android SDK", body), *body])
    if guides := sorted((ROOT / "docs").glob("migration-*.md"), reverse=True):
        head = "---\ntitle: PostHog Android SDK migration guides\nsidebar: Docs\nshowTitle: true\n---"
        write("posthog-android-migration.md", [head, *(g.read_text().strip() for g in guides)])


if __name__ == "__main__":
    main()
