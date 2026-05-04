#!/usr/bin/env node
// Compares packages modified in this PR against packages declared in any
// changeset added/modified in this PR. Writes a markdown comment body to
// stdout in GITHUB_OUTPUT format. Never exits non-zero — the workflow only
// uses this to surface a warning, not to gate the PR.

import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const baseRef = process.env.BASE_REF || 'main';

const sh = (cmd) => execSync(cmd, { encoding: 'utf8' }).trim();

// 1. Workspace package directories → package names.
const workspaceFile = readFileSync('pnpm-workspace.yaml', 'utf8');
const pkgDirs = [...workspaceFile.matchAll(/^\s*-\s*"([^"]+)"\s*$/gm)].map((m) => m[1]);
const dirToName = {};
for (const dir of pkgDirs) {
    const pj = join(dir, 'package.json');
    if (!existsSync(pj)) continue;
    const name = JSON.parse(readFileSync(pj, 'utf8')).name;
    if (name) dirToName[dir] = name;
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

if (changesetFiles.length === 0) {
    writeOutput('');
    process.exit(0);
}

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

const declaredList = [...declared]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([n, b]) => `- \`${n}\` — ${b}`)
    .join('\n');

const lines = [];
if (missing.length === 0 && extra.length === 0) {
    lines.push('### Changeset coverage looks good ✅');
    lines.push('');
    lines.push("This PR's changesets cover every modified workspace package.");
    lines.push('');
    lines.push('**Declared:**');
    lines.push(declaredList || '_(none)_');
} else {
    lines.push('### Possible changeset mismatch ⚠️');
    lines.push('');
    lines.push("This is informational — the PR is not blocked. You can ignore it if the mismatch is intentional.");
    lines.push('');
    if (missing.length > 0) {
        lines.push('**Modified in this PR but not in any changeset:**');
        for (const n of missing) lines.push(`- \`${n}\``);
        lines.push('');
        lines.push(
            'If this package should ship the change, add it to the changeset frontmatter:',
        );
        lines.push('');
        lines.push('```');
        lines.push('---');
        for (const n of missing) lines.push(`"${n}": patch`);
        lines.push('---');
        lines.push('```');
        lines.push('');
    }
    if (extra.length > 0) {
        lines.push('**Declared in a changeset but no source files modified:**');
        for (const n of extra) lines.push(`- \`${n}\``);
        lines.push('');
        lines.push(
            'Double-check this is intentional — for example, releasing a previously-merged change.',
        );
        lines.push('');
    }
    lines.push('**Changesets in this PR:**');
    lines.push(declaredList || '_(none)_');
}

writeOutput(lines.join('\n'));
