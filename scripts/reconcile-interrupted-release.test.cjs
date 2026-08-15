"use strict";

const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const {
  chmodSync,
  cpSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} = require("node:fs");
const { tmpdir } = require("node:os");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "..");
const trustedRepositoryId = "1332440403";
const trustedRepository = "tapziq/Tapziq";
const { sourceWithVersion } = require("./prepare-release-version.cjs");

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    ...options,
  });
  if (result.status !== 0) {
    throw new Error(result.stderr || result.stdout || `${command} failed`);
  }
  return result.stdout.trim();
}

function git(cwd, ...args) {
  return run("git", args, { cwd });
}

function createFixture({
  fullReconcile = false,
  generatedReleaseCommit = false,
  releaseCommit = false,
  withTag = false,
} = {}) {
  const root = mkdtempSync(path.join(tmpdir(), "tapziq-reconcile-test."));
  const work = path.join(root, "work");
  const remote = path.join(root, "remote.git");
  const bin = path.join(root, "bin");
  cpSync(repositoryRoot, work, {
    recursive: true,
    filter(source) {
      const relative = path.relative(repositoryRoot, source);
      return relative === ""
        || relative === "package.json"
        || relative === "release.config.cjs"
        || relative === "app"
        || relative === "app/build.gradle.kts"
        || relative === "scripts"
        || relative.startsWith(`scripts${path.sep}`);
    },
  });
  rmSync(path.join(work, "scripts", "reconcile-interrupted-release.test.cjs"));
  require("node:fs").symlinkSync(
    path.join(repositoryRoot, "node_modules"),
    path.join(work, "node_modules"),
    "dir",
  );
  if (fullReconcile) {
    const packageScript = path.join(work, "scripts", "package-semantic-release.sh");
    writeFileSync(packageScript, `#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 3 ]]
[[ "$1" == 0.2.0 ]]
[[ "$2" == "$(git rev-parse HEAD)" ]]
[[ "$3" == --allow-existing-tag ]]
mkdir -p dist/release
printf '%s\n' "\${TAPZIQ_TEST_APK_CONTENT:-production apk}" > dist/release/Tapziq-v0.2.0.apk
printf 'license\n' > dist/release/LICENSE.txt
printf 'checksums\n' > dist/release/SHA256SUMS
printf 'notices\n' > dist/release/THIRD_PARTY_NOTICES.md
printf '%s\n' "$*" > "$TAPZIQ_TEST_PACKAGE_RECORD"
`);
    chmodSync(packageScript, 0o755);
    const verifyScript = path.join(work, "scripts", "verify-published-release.sh");
    writeFileSync(verifyScript, `#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 2 ]]
[[ "$1" == 0.2.0 ]]
[[ "$2" == "$(git rev-parse HEAD)" ]]
printf '%s\n' "$*" > "$TAPZIQ_TEST_VERIFY_RECORD"
`);
    chmodSync(verifyScript, 0o755);
  }
  run("git", ["init", "--bare", remote]);
  run("git", ["init", "-b", "main", work]);
  git(work, "config", "user.name", "Release Test");
  git(work, "config", "user.email", "release@example.invalid");
  git(work, "config", "commit.gpgsign", "false");
  git(work, "config", "tag.gpgsign", "false");
  writeFileSync(path.join(work, "fixture.txt"), "baseline\n");
  git(work, "add", ".");
  git(work, "commit", "-m", "chore: baseline");
  git(work, "tag", "v0.1.0");
  if (releaseCommit) {
    writeFileSync(path.join(work, "fixture.txt"), "baseline\nfeature\n");
    git(work, "add", "fixture.txt");
    git(work, "commit", "-m", "feat: recover this release");
  }
  const workflowHead = git(work, "rev-parse", "HEAD");
  if (generatedReleaseCommit) {
    if (!releaseCommit) {
      throw new Error("A generated release commit requires a product commit.");
    }
    const sourcePath = path.join(work, "app", "build.gradle.kts");
    writeFileSync(
      sourcePath,
      sourceWithVersion(readFileSync(sourcePath, "utf8"), "0.2.0"),
    );
    git(work, "add", "app/build.gradle.kts");
    git(work, "commit", "-m", "chore(release): 0.2.0 [skip ci]");
  }
  const releaseHead = git(work, "rev-parse", "HEAD");
  if (withTag) {
    git(work, "tag", "v0.2.0");
    git(work, "notes", "--ref", "semantic-release-v0.2.0", "add", "-m", '{"channels":[null]}', "v0.2.0");
  }
  git(work, "remote", "add", "origin", remote);
  git(work, "push", "origin", "main", "--tags");
  if (withTag) {
    git(work, "push", "origin", "refs/notes/semantic-release-v0.2.0");
  }
  if (generatedReleaseCommit) {
    git(work, "checkout", "--detach", workflowHead);
  }
  require("node:fs").mkdirSync(bin);
  const gh = path.join(bin, "gh");
  writeFileSync(gh, `#!/usr/bin/env node
const { createHash } = require("node:crypto");
const { existsSync, readFileSync, writeFileSync } = require("node:fs");
const args = process.argv.slice(2);
if (args[0] !== "api") process.exit(70);
const endpoint = args[1];
const statePath = process.env.TAPZIQ_TEST_GH_STATE;
function field(name) {
  for (let index = 2; index < args.length - 1; index += 1) {
    if (["-f", "-F"].includes(args[index]) && args[index + 1].startsWith(name + "=")) {
      return args[index + 1].slice(name.length + 1);
    }
  }
  return undefined;
}
function loadState() {
  return statePath && existsSync(statePath)
    ? JSON.parse(readFileSync(statePath, "utf8"))
    : null;
}
function saveState(state) {
  writeFileSync(statePath, JSON.stringify(state));
}
function releaseJson(state, draft = true) {
  return {
    id: 42,
    upload_url: "https://uploads.github.com/repos/${trustedRepository}/releases/42/assets{?name,label}",
    tag_name: state.tag_name,
    target_commitish: state.target_commitish,
    name: state.name,
    body: state.body,
    draft,
    prerelease: false,
    immutable: !draft,
    assets: state.assets,
  };
}
const methodIndex = args.indexOf("--method");
const method = methodIndex === -1 ? "GET" : args[methodIndex + 1];
let state = loadState();
if (endpoint === "repositories/${trustedRepositoryId}") {
  process.stdout.write(JSON.stringify({id:${trustedRepositoryId},archived:false,disabled:false,default_branch:"main",full_name:"${trustedRepository}"}));
} else if (endpoint === "repositories/${trustedRepositoryId}/releases/latest") {
  process.stdout.write(JSON.stringify({tag_name:"v0.1.0",target_commitish:process.env.TAPZIQ_TEST_BASELINE_SHA}));
} else if (/^repositories\\/${trustedRepositoryId}\\/releases\\/tags\\/v0\\.[23]\\.0$/.test(endpoint)) {
  if (state && state.published && endpoint.endsWith("/" + state.tag_name)) {
    process.stdout.write(JSON.stringify(releaseJson(state, false)));
  } else {
    process.stderr.write("gh: Not Found (HTTP 404)\\n");
    process.exit(1);
  }
} else if (endpoint === "repositories/${trustedRepositoryId}/releases?per_page=100") {
  process.stdout.write(JSON.stringify(state ? [releaseJson(state, !state.published)] : []));
} else if (endpoint === "repositories/${trustedRepositoryId}/releases" && method === "POST") {
  if (process.env.TAPZIQ_TEST_RECONCILE !== "1" || state) process.exit(72);
  state = {
    tag_name: field("tag_name"),
    target_commitish: field("target_commitish"),
    name: field("name"),
    body: field("body"),
    assets: [],
    deleted_assets: 0,
    published: false,
  };
  saveState(state);
  process.stdout.write(JSON.stringify(releaseJson(state)));
} else if (endpoint.startsWith("https://uploads.github.com/") && method === "POST") {
  if (!state || state.published) process.exit(73);
  const failAfter = Number(process.env.TAPZIQ_TEST_FAIL_UPLOAD_AFTER);
  if (Number.isSafeInteger(failAfter) && state.assets.length >= failAfter) process.exit(77);
  const upload = new URL(endpoint);
  if (upload.pathname !== "/repos/${trustedRepository}/releases/42/assets") process.exit(74);
  const content = readFileSync(0);
  const headerIndex = args.indexOf("-H");
  const contentType = args[headerIndex + 1].replace(/^Content-Type: /, "");
  const asset = {
    id: Math.max(0, ...state.assets.map(({ id }) => id)) + 1,
    name: upload.searchParams.get("name"),
    label: upload.searchParams.get("label"),
    content_type: contentType,
    state: "uploaded",
    size: content.length,
    digest: "sha256:" + createHash("sha256").update(content).digest("hex"),
  };
  state.assets.push(asset);
  saveState(state);
  process.stdout.write(JSON.stringify(asset));
} else if (endpoint === "repositories/${trustedRepositoryId}/releases/42" && method === "GET") {
  if (!state) process.exit(75);
  process.stdout.write(JSON.stringify(releaseJson(state)));
} else if (/^repositories\\/${trustedRepositoryId}\\/releases\\/assets\\/\\d+$/.test(endpoint) && method === "DELETE") {
  if (!state || state.published) process.exit(78);
  const assetId = Number(endpoint.split("/").at(-1));
  const assetIndex = state.assets.findIndex(({ id }) => id === assetId);
  if (assetIndex === -1) process.exit(79);
  state.assets.splice(assetIndex, 1);
  state.deleted_assets += 1;
  saveState(state);
} else if (endpoint === "repositories/${trustedRepositoryId}/releases/42" && method === "PATCH") {
  if (!state || state.assets.length !== 4) process.exit(76);
  state.published = true;
  saveState(state);
  process.stdout.write(JSON.stringify(releaseJson(state, false)));
} else {
  process.stderr.write("unexpected API: " + endpoint + "\\n");
  process.exit(71);
}
`);
  chmodSync(gh, 0o755);
  return {
    root,
    work,
    remote,
    bin,
    head: workflowHead,
    releaseHead,
    baseline: git(work, "rev-list", "-n", "1", "v0.1.0"),
  };
}

function runHelper(fixture, environment = {}) {
  const output = path.join(fixture.root, "github-output");
  const result = spawnSync(process.execPath, [path.join(fixture.work, "scripts", "reconcile-interrupted-release.cjs")], {
    cwd: fixture.work,
    encoding: "utf8",
    env: {
      ...process.env,
      PATH: `${fixture.bin}:${process.env.PATH}`,
      GITHUB_OUTPUT: output,
      GITHUB_REF: "refs/heads/main",
      GITHUB_REPOSITORY: trustedRepository,
      GITHUB_REPOSITORY_ID: trustedRepositoryId,
      GITHUB_SHA: fixture.head,
      GIT_CONFIG_COUNT: "1",
      GIT_CONFIG_KEY_0: `url.${fixture.remote}.insteadOf`,
      GIT_CONFIG_VALUE_0: `https://github.com/${trustedRepository}.git`,
      TAPZIQ_TEST_BASELINE_SHA: fixture.baseline,
      TAPZIQ_TEST_GH_STATE: path.join(fixture.root, "gh-state.json"),
      TAPZIQ_TEST_PACKAGE_RECORD: path.join(fixture.root, "package-record"),
      TAPZIQ_TEST_VERIFY_RECORD: path.join(fixture.root, "verify-record"),
      ...environment,
    },
  });
  return {
    ...result,
    output: result.status === 0 ? readFileSync(output, "utf8") : "",
  };
}

test("no release tag reports handled=false without mutating GitHub", () => {
  const fixture = createFixture({ releaseCommit: true });
  try {
    const result = runHelper(fixture);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.output, "handled=false\n");
    assert.match(result.stdout, /^handled=false$/m);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("a generated source-version commit is recovered before a tag exists", () => {
  const fixture = createFixture({
    generatedReleaseCommit: true,
    releaseCommit: true,
  });
  try {
    const result = runHelper(fixture);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.output, "handled=false\n");
    assert.match(result.stdout, /Recovered generated source-version commit/);
    assert.equal(git(fixture.work, "rev-parse", "HEAD"), fixture.releaseHead);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("an unrelated remote-main advance is not treated as a release commit", () => {
  const fixture = createFixture({ releaseCommit: true });
  try {
    writeFileSync(path.join(fixture.work, "fixture.txt"), "baseline\nfeature\nunrelated\n");
    git(fixture.work, "add", "fixture.txt");
    git(fixture.work, "commit", "-m", "ci: unrelated branch advance");
    git(fixture.work, "push", "origin", "main");
    git(fixture.work, "checkout", "--detach", fixture.head);

    const result = runHelper(fixture);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /advanced beyond GITHUB_SHA with a non-release commit/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("a release-shaped commit cannot hide other Gradle changes", () => {
  const fixture = createFixture({ releaseCommit: true });
  try {
    const sourcePath = path.join(fixture.work, "app", "build.gradle.kts");
    writeFileSync(
      sourcePath,
      sourceWithVersion(readFileSync(sourcePath, "utf8"), "0.2.0")
        + "\n// unexpected release-time change\n",
    );
    git(fixture.work, "add", "app/build.gradle.kts");
    git(fixture.work, "commit", "-m", "chore(release): 0.2.0 [skip ci]");
    git(fixture.work, "push", "origin", "main");
    git(fixture.work, "checkout", "--detach", fixture.head);

    const result = runHelper(fixture);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /changed non-version Gradle metadata/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("an orphan tag is rejected when its version is not analyzer-derived", () => {
  const fixture = createFixture({ releaseCommit: true, withTag: true });
  try {
    git(fixture.work, "tag", "-d", "v0.2.0");
    git(fixture.work, "update-ref", "-d", "refs/notes/semantic-release-v0.2.0");
    git(fixture.work, "tag", "v0.3.0");
    git(fixture.work, "notes", "--ref", "semantic-release-v0.3.0", "add", "-m", '{"channels":[null]}', "v0.3.0");
    git(fixture.work, "push", fixture.remote, ":refs/tags/v0.2.0", "refs/tags/v0.3.0");
    git(fixture.work, "push", fixture.remote, ":refs/notes/semantic-release-v0.2.0");
    git(fixture.work, "push", fixture.remote, "refs/notes/semantic-release-v0.3.0");
    const result = runHelper(fixture);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /v0\.3\.0 is not the expected minor release after v0\.1\.0/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("an orphan tag without the exact semantic-release note is rejected", () => {
  const fixture = createFixture({ releaseCommit: true, withTag: true });
  try {
    git(fixture.work, "update-ref", "-d", "refs/notes/semantic-release-v0.2.0");
    git(fixture.work, "push", fixture.remote, ":refs/notes/semantic-release-v0.2.0");
    const result = runHelper(fixture);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Remote semantic-release note is missing/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("an orphan release is rejected after main advances past its tag", () => {
  const fixture = createFixture({ releaseCommit: true, withTag: true });
  try {
    writeFileSync(path.join(fixture.work, "fixture.txt"), "baseline\nfeature\nfollow-up\n");
    git(fixture.work, "add", "fixture.txt");
    git(fixture.work, "commit", "-m", "ci: advance main after interrupted release");
    git(fixture.work, "push", "origin", "main");
    fixture.head = git(fixture.work, "rev-parse", "HEAD");

    const result = runHelper(fixture);
    assert.equal(result.status, 1);
    assert.match(
      result.stderr,
      /newer reachable semantic-release tag or note.*not exactly on GITHUB_SHA: v0\.2\.0/s,
    );
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("a future semantic-release note without its tag fails closed", () => {
  const fixture = createFixture({ releaseCommit: true, withTag: true });
  try {
    git(fixture.work, "tag", "-d", "v0.2.0");
    git(fixture.work, "push", fixture.remote, ":refs/tags/v0.2.0");

    const result = runHelper(fixture);
    assert.equal(result.status, 1);
    assert.match(
      result.stderr,
      /Semantic-release note v0\.2\.0 has no matching local tag.*ambiguous/s,
    );
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("an exact orphan tag resumes a matching partial draft and publishes once", () => {
  const fixture = createFixture({
    fullReconcile: true,
    generatedReleaseCommit: true,
    releaseCommit: true,
    withTag: true,
  });
  try {
    const interrupted = runHelper(fixture, {
      TAPZIQ_TEST_FAIL_UPLOAD_AFTER: "2",
      TAPZIQ_TEST_RECONCILE: "1",
    });
    assert.equal(interrupted.status, 1);
    assert.match(interrupted.stderr, /GitHub asset upload failed/);
    const partialState = JSON.parse(
      readFileSync(path.join(fixture.root, "gh-state.json"), "utf8"),
    );
    assert.equal(partialState.published, false);
    assert.equal(partialState.assets.length, 2);

    rmSync(path.join(fixture.work, "dist"), { recursive: true, force: true });
    rmSync(path.join(fixture.root, "github-output"), { force: true });
    git(fixture.work, "checkout", "--detach", fixture.head);
    const result = runHelper(fixture, {
      TAPZIQ_TEST_APK_CONTENT: "rebuilt production apk",
      TAPZIQ_TEST_RECONCILE: "1",
    });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.output, "handled=true\n");
    assert.equal(
      readFileSync(path.join(fixture.root, "package-record"), "utf8"),
      `0.2.0 ${fixture.releaseHead} --allow-existing-tag\n`,
    );
    assert.equal(
      readFileSync(path.join(fixture.root, "verify-record"), "utf8"),
      `0.2.0 ${fixture.releaseHead}\n`,
    );
    const state = JSON.parse(readFileSync(path.join(fixture.root, "gh-state.json"), "utf8"));
    assert.equal(state.published, true);
    assert.equal(state.deleted_assets, 1);
    assert.deepEqual(
      state.assets
        .map(({ name, label }) => ({ name, label }))
        .sort((left, right) => left.name.localeCompare(right.name)),
      [
        { name: "LICENSE.txt", label: "Apache License 2.0" },
        { name: "SHA256SUMS", label: "SHA-256 checksums" },
        { name: "Tapziq-v0.2.0.apk", label: "Production-signed Android APK" },
        { name: "THIRD_PARTY_NOTICES.md", label: "Third-party notices" },
      ],
    );

    rmSync(path.join(fixture.work, "dist"), { recursive: true, force: true });
    rmSync(path.join(fixture.root, "github-output"), { force: true });
    rmSync(path.join(fixture.root, "package-record"), { force: true });
    git(fixture.work, "checkout", "--detach", fixture.head);
    const rerun = runHelper(fixture, { TAPZIQ_TEST_RECONCILE: "1" });
    assert.equal(rerun.status, 0, rerun.stderr);
    assert.equal(rerun.output, "handled=true\n");
    assert.equal(existsSync(path.join(fixture.root, "package-record")), false);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});
