#!/usr/bin/env node
// Compares packages modified in this PR against packages declared in any
// changeset added/modified in this PR. Writes a markdown comment body to
// stdout in GITHUB_OUTPUT format. Never exits non-zero — the workflow only
// uses this to surface a warning, not to gate the PR.

import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';

const baseRef = process.env.BASE_REF || 'main';

const sh = (cmd) => execSync(cmd, { encoding: 'utf8' }).trim();

// 1. Workspace package directories → package names. Use pnpm itself as the
//    source of truth so we handle globs, exclusions, and the catalog correctly.
const cwd = process.cwd();
const workspaceListing = JSON.parse(sh('pnpm m ls --json --depth=-1'));
const dirToName = {};
for (const entry of workspaceListing) {
    if (!entry.name) continue; // workspace root has no name field
    if (entry.path === cwd) continue;
    const relPath = entry.path.startsWith(cwd + '/')
        ? entry.path.slice(cwd.length + 1)
        : entry.path;
    dirToName[relPath] = entry.name;
}
const knownNames = new Set(Object.values(dirToName));

// 2. Diff vs base.
const mergeBase = sh(`git merge-base origin/${baseRef} HEAD`);
const changedFiles = sh(`git diff --name-only ${mergeBase}...HEAD`).split('\n').filter(Boolean);

// 3. Map changed files → affected packages.
const ignoreSuffixes = ['/CHANGELOG.md', '/package.json'];
const affected = new Set();
for (const file of changedFiles) {
    if (file.startsWith('.changeset/')) continue;
    if (ignoreSuffixes.some((s) => file.endsWith(s))) continue;
    for (const [dir, name] of Object.entries(dirToName)) {
        if (file === dir || file.startsWith(dir + '/')) {
            affected.add(name);
            break;
        }
    }
}

// 4. Find changeset files added or modified in this PR.
const changesetFiles = sh(
    `git diff --name-only --diff-filter=AM ${mergeBase}...HEAD -- .changeset/`,
)
    .split('\n')
    .filter((f) => f.endsWith('.md') && !f.endsWith('README.md'));

const writeOutput = (body) => {
    if (!body) {
        process.stdout.write('body=\n');
    } else {
        process.stdout.write(`body<<CHANGESET_COVERAGE_EOF\n${body}\nCHANGESET_COVERAGE_EOF\n`);
    }
};

// 5. Parse frontmatter from each changeset file.
const declared = new Map();
for (const file of changesetFiles) {
    if (!existsSync(file)) continue;
    const content = readFileSync(file, 'utf8');
    const fm = content.match(/^---\r?\n([\s\S]*?)\r?\n---/);
    if (!fm) continue;
    for (const line of fm[1].split(/\r?\n/)) {
        const m = line.match(/^\s*"?([^"\s:]+)"?\s*:\s*(patch|minor|major)\s*$/);
        if (m) declared.set(m[1], m[2]);
    }
}

// 6. Compare.
const missing = [...affected].filter((n) => !declared.has(n)).sort();
const extra = [...declared.keys()].filter((n) => !affected.has(n) && knownNames.has(n)).sort();
const unknownDeclared = [...declared.keys()].filter((n) => !knownNames.has(n)).sort();
const noChangesetButPackagesModified = changesetFiles.length === 0 && affected.size > 0;

const hasIssue =
    missing.length > 0 ||
    extra.length > 0 ||
    unknownDeclared.length > 0 ||
    noChangesetButPackagesModified;

if (!hasIssue) {
    writeOutput('');
    process.exit(0);
}

const declaredList = [...declared]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([n, b]) => `- \`${n}\` — ${b}`)
    .join('\n');

const summary = (() => {
    if (noChangesetButPackagesModified) {
        const arr = [...affected].sort();
        return arr.length === 1
            ? `\`${arr[0]}\` is modified but this PR has no changeset`
            : `${arr.length} packages modified but this PR has no changeset`;
    }
    const issues = [];
    if (missing.length) issues.push(`${missing.length} undeclared`);
    if (unknownDeclared.length) issues.push(`${unknownDeclared.length} unknown`);
    if (extra.length) issues.push(`${extra.length} extra`);
    if (unknownDeclared.length === 1 && !missing.length && !extra.length) {
        return `Changeset declares \`${unknownDeclared[0]}\` which isn't a known workspace package`;
    }
    if (missing.length === 1 && !extra.length && !unknownDeclared.length) {
        return `\`${missing[0]}\` is modified but not declared in any changeset`;
    }
    if (extra.length === 1 && !missing.length && !unknownDeclared.length) {
        return `Changeset declares \`${extra[0]}\` but no source files in that package changed`;
    }
    return `Possible changeset mismatch — ${issues.join(', ')}`;
})();

const inner = [];
inner.push(
    'This is informational — the PR is not blocked. Click the triangle above to collapse, or push a fix and this comment will auto-delete.',
);
inner.push('');
if (noChangesetButPackagesModified) {
    inner.push('**Modified in this PR but no changeset added:**');
    for (const n of [...affected].sort()) inner.push(`- \`${n}\``);
    inner.push('');
    inner.push('If this change should ship, run `pnpm changeset` and select a bump level.');
    inner.push(
        "If it isn't user-facing (refactor with no behavior change, internal tooling, generated files), no action needed.",
    );
} else {
    if (missing.length > 0) {
        inner.push('**Modified in this PR but not in any changeset:**');
        for (const n of missing) inner.push(`- \`${n}\``);
        inner.push('');
        inner.push('If this package should ship the change, add it to the changeset frontmatter:');
        inner.push('');
        inner.push('```');
        inner.push('---');
        for (const n of missing) inner.push(`"${n}": patch`);
        inner.push('---');
        inner.push('```');
        inner.push('');
    }
    if (unknownDeclared.length > 0) {
        inner.push('**Declared in a changeset but not a known workspace package (typo?):**');
        for (const n of unknownDeclared) inner.push(`- \`${n}\``);
        inner.push('');
        const sample = [...knownNames].sort().slice(0, 5);
        inner.push(`Valid workspace package names: \`${sample.join('`, `')}\`${knownNames.size > 5 ? ', …' : ''}`);
        inner.push('');
    }
    if (extra.length > 0) {
        inner.push('**Declared in a changeset but no source files modified:**');
        for (const n of extra) inner.push(`- \`${n}\``);
        inner.push('');
        inner.push(
            'Double-check this is intentional — for example, releasing a previously-merged change.',
        );
        inner.push('');
    }
    inner.push('**Changesets in this PR:**');
    inner.push(declaredList || '_(none)_');
}

const body = `<!-- changeset-coverage -->\n<details open>\n<summary>⚠️ ${summary}</summary>\n\n${inner.join('\n')}\n\n</details>`;
writeOutput(body);
