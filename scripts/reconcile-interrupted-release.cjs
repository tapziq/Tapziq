#!/usr/bin/env node
"use strict";

const { execFileSync, spawnSync } = require("node:child_process");
const {
  appendFileSync,
  chmodSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} = require("node:fs");
const { createHash, randomBytes } = require("node:crypto");
const { tmpdir } = require("node:os");
const { pathToFileURL } = require("node:url");
const path = require("node:path");
const process = require("node:process");
const semver = require("semver");
const {
  sourceVersionCode,
  sourceWithVersion,
  verifySourceVersion,
} = require("./prepare-release-version.cjs");

const TRUSTED_REPOSITORY_ID = "1332440403";
const TAG_PATTERN = /^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
const RELEASE_COMMIT_PATTERN =
  /^chore\(release\): ((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)) \[skip ci\]$/;
const RECOVERY_MANIFEST_PATH = "release/interrupted-release-recoveries.json";
const PUBLISHED_VERIFIER_PATH = "scripts/verify-published-release.sh";
const VULNERABLE_REMOTE_TAG_AWK =
  '    END { print peeled != "" ? peeled : direct }';
const PORTABLE_REMOTE_TAG_AWK =
  '    END { print (peeled != "" ? peeled : direct) }';
const RELEASE_ASSETS = (version) => [
  {
    name: `Tapziq-v${version}.apk`,
    label: "Production-signed Android APK",
    contentType: "application/vnd.android.package-archive",
  },
  {
    name: "LICENSE.txt",
    label: "Apache License 2.0",
    contentType: "text/plain",
  },
  {
    name: "SHA256SUMS",
    label: "SHA-256 checksums",
    contentType: "text/plain",
  },
  {
    name: "THIRD_PARTY_NOTICES.md",
    label: "Third-party notices",
    contentType: "text/markdown",
  },
];
const RELEASE_ASSET_NAMES = (version) => RELEASE_ASSETS(version).map(({ name }) => name);
const CONVENTIONAL_COMMITS = {
  preset: "conventionalcommits",
  presetConfig: {},
};

function fail(message) {
  throw new Error(message);
}

function run(command, args, options = {}) {
  const result = execFileSync(command, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    stdio: ["ignore", "pipe", "pipe"],
    ...options,
  });
  return typeof result === "string" ? result.trim() : result;
}

function git(...args) {
  return run("git", args);
}

function ghApi(endpoint, fields = []) {
  return JSON.parse(run("gh", ["api", endpoint, ...fields]));
}

function ghApiWithoutResponse(endpoint, fields = []) {
  run("gh", ["api", endpoint, ...fields]);
}

function releaseApiOrNull(endpoint) {
  try {
    return ghApi(endpoint);
  } catch (error) {
    if (error.status === 1 && /HTTP 404|Not Found/.test(error.stderr || "")) {
      return null;
    }
    throw error;
  }
}

function ghApiWithFileInput(endpoint, fields, localPath) {
  const result = spawnSync("gh", ["api", endpoint, ...fields, "--input", localPath], {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    const stderr = result.stderr.trim();
    const error = new Error(
      stderr === ""
        ? "GitHub asset upload failed."
        : `GitHub asset upload failed: ${stderr}`,
    );
    error.status = result.status;
    error.stderr = stderr;
    throw error;
  }
}

function setHandled(value) {
  const outputPath = process.env.GITHUB_OUTPUT;
  if (outputPath) {
    appendFileSync(outputPath, `handled=${value}\n`, "utf8");
  }
  process.stdout.write(`handled=${value}\n`);
}

function gitFile(ref, relativePath) {
  return execFileSync("git", ["show", `${ref}:${relativePath}`], {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    stdio: ["ignore", "pipe", "pipe"],
  });
}

function hasExactKeys(value, keys) {
  return value !== null
    && !Array.isArray(value)
    && typeof value === "object"
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...keys].sort());
}

function loadRecoveryManifest(workflowCommit) {
  let manifest;
  try {
    manifest = JSON.parse(gitFile(workflowCommit, RECOVERY_MANIFEST_PATH));
  } catch (error) {
    fail(`Interrupted-release recovery manifest is unreadable: ${error.message}`);
  }
  if (!hasExactKeys(manifest, ["recoveries"]) || !Array.isArray(manifest.recoveries)) {
    fail("Interrupted-release recovery manifest has an invalid top-level schema.");
  }

  const byTag = new Map();
  const commits = new Set();
  for (const recovery of manifest.recoveries) {
    if (!hasExactKeys(recovery, [
      "commit",
      "proofreadRowsBeforeLetters",
      "tag",
    ])) {
      fail("Interrupted-release recovery manifest contains an invalid entry.");
    }
    if (!TAG_PATTERN.test(recovery.tag)) {
      fail("Interrupted-release recovery manifest contains an invalid tag.");
    }
    if (!/^[0-9a-f]{40}$/.test(recovery.commit)) {
      fail("Interrupted-release recovery manifest requires a full lowercase commit SHA.");
    }
    if (![0, 1].includes(recovery.proofreadRowsBeforeLetters)) {
      fail("Interrupted-release recovery manifest requires a keyboard row count of 0 or 1.");
    }
    if (byTag.has(recovery.tag) || commits.has(recovery.commit)) {
      fail("Interrupted-release recovery manifest contains a duplicate tag or commit.");
    }
    byTag.set(recovery.tag, Object.freeze({ ...recovery }));
    commits.add(recovery.commit);
  }
  return byTag;
}

function validateGeneratedReleaseCommit(parentCommit, releaseCommit, expectedTag) {
  const commitRecord = git("rev-list", "--parents", "-n", "1", releaseCommit)
    .split(/\s+/);
  if (
    commitRecord.length !== 2
    || commitRecord[0] !== releaseCommit
    || commitRecord[1] !== parentCommit
  ) {
    fail("Configured interrupted release is not one direct generated release commit.");
  }
  const subject = git("show", "-s", "--format=%s", releaseCommit);
  const subjectMatch = RELEASE_COMMIT_PATTERN.exec(subject);
  if (subjectMatch === null || `v${subjectMatch[1]}` !== expectedTag) {
    fail("Configured interrupted release has an invalid generated release subject.");
  }
  const changedPaths = git(
    "diff-tree",
    "--no-commit-id",
    "--name-only",
    "-r",
    parentCommit,
    releaseCommit,
  ).split("\n").filter(Boolean);
  if (changedPaths.length !== 1 || changedPaths[0] !== "app/build.gradle.kts") {
    fail("Configured interrupted release changed files outside source version metadata.");
  }
  try {
    verifySourceVersion(subjectMatch[1], {
      repositoryRoot,
      ref: releaseCommit,
    });
    const parentSource = gitFile(parentCommit, "app/build.gradle.kts");
    const releaseSource = gitFile(releaseCommit, "app/build.gradle.kts");
    if (releaseSource !== sourceWithVersion(parentSource, subjectMatch[1])) {
      fail("Configured interrupted release changed non-version Gradle metadata.");
    }
  } catch (error) {
    fail(`Configured interrupted release has invalid source metadata: ${error.message}`);
  }
  return subjectMatch[1];
}

function isOnFirstParentChain(ancestor, descendant) {
  return git("rev-list", "--first-parent", descendant)
    .split("\n")
    .includes(ancestor);
}

function freezeWorkflowSmokeScript(workflowCommit) {
  const temporaryRoot = process.env.RUNNER_TEMP || tmpdir();
  const directory = mkdtempSync(path.join(temporaryRoot, "tapziq-recovery."));
  const scriptPath = path.join(directory, "smoke-test-release-apk.sh");
  try {
    writeFileSync(
      scriptPath,
      gitFile(workflowCommit, "scripts/smoke-test-release-apk.sh"),
      { encoding: "utf8", flag: "wx", mode: 0o700 },
    );
    chmodSync(scriptPath, 0o700);
    return { directory, scriptPath };
  } catch (error) {
    rmSync(directory, { recursive: true, force: true });
    throw error;
  }
}

function approvedRecoveryVerifier(workflowCommit, releaseCommit) {
  const historicalVerifier = gitFile(releaseCommit, PUBLISHED_VERIFIER_PATH);
  const vulnerableIndex = historicalVerifier.indexOf(VULNERABLE_REMOTE_TAG_AWK);
  if (
    vulnerableIndex === -1
    || historicalVerifier.indexOf(
      VULNERABLE_REMOTE_TAG_AWK,
      vulnerableIndex + VULNERABLE_REMOTE_TAG_AWK.length,
    ) !== -1
    || historicalVerifier.includes(PORTABLE_REMOTE_TAG_AWK)
  ) {
    fail(
      "Configured interrupted release does not contain exactly one audited "
        + "published-verifier portability defect.",
    );
  }
  const approvedVerifier = historicalVerifier.replace(
    VULNERABLE_REMOTE_TAG_AWK,
    PORTABLE_REMOTE_TAG_AWK,
  );
  if (gitFile(workflowCommit, PUBLISHED_VERIFIER_PATH) !== approvedVerifier) {
    fail(
      "The workflow published verifier differs from the historical verifier "
        + "beyond the audited portability repair.",
    );
  }
  return approvedVerifier;
}

function runRecoveryVerifier(contents, version, releaseCommit) {
  const verifierPath = path.join(
    repositoryRoot,
    "scripts",
    `.tapziq-recovery-verifier.${process.pid}.${randomBytes(12).toString("hex")}.sh`,
  );
  try {
    writeFileSync(verifierPath, contents, {
      encoding: "utf8",
      flag: "wx",
      mode: 0o700,
    });
    chmodSync(verifierPath, 0o700);
    run(verifierPath, [version, releaseCommit], { stdio: "inherit" });
  } finally {
    rmSync(verifierPath, { force: true });
  }
}

function childEnvironment(overrides, omittedNames) {
  const environment = { ...process.env, ...overrides };
  for (const name of omittedNames) {
    delete environment[name];
  }
  return environment;
}

function parseRemoteRef(output, expectedRef) {
  const rows = output === "" ? [] : output.split("\n");
  if (rows.length !== 1) {
    fail(`Expected exactly one remote ${expectedRef} reference.`);
  }
  const [sha, ref, extra] = rows[0].split(/\s+/);
  if (extra !== undefined || ref !== expectedRef || !/^[0-9a-f]{40}$/.test(sha)) {
    fail(`Remote ${expectedRef} reference is malformed.`);
  }
  return sha;
}

function resolveRemoteTag(tag) {
  const directRef = `refs/tags/${tag}`;
  const peeledRef = `${directRef}^{}`;
  const output = git("ls-remote", repositoryUrl, directRef, peeledRef);
  const rows = output === "" ? [] : output.split("\n");
  if (rows.length < 1 || rows.length > 2) {
    fail(`Expected one remote tag named ${tag}.`);
  }
  const refs = new Map();
  for (const row of rows) {
    const [sha, ref, extra] = row.split(/\s+/);
    if (
      extra !== undefined
      || ![directRef, peeledRef].includes(ref)
      || refs.has(ref)
      || !/^[0-9a-f]{40}$/.test(sha)
    ) {
      fail(`Remote release tag ${tag} is malformed.`);
    }
    refs.set(ref, sha);
  }
  if (!refs.has(directRef)) {
    fail(`Remote release tag ${tag} is missing.`);
  }
  return refs.get(peeledRef) || refs.get(directRef);
}

function verifyPinnedLightweightTag({ commit, tag }) {
  const localRef = `refs/tags/${tag}`;
  if (
    git("cat-file", "-t", localRef) !== "commit"
    || git("rev-parse", "--verify", localRef) !== commit
  ) {
    fail("Configured interrupted-release tag is not the exact local lightweight tag.");
  }
  const remoteRef = `refs/tags/${tag}`;
  const remoteOutput = git("ls-remote", repositoryUrl, remoteRef, `${remoteRef}^{}`);
  const rows = remoteOutput === "" ? [] : remoteOutput.split("\n");
  if (rows.length !== 1 || rows[0] !== `${commit}\t${remoteRef}`) {
    fail("Configured interrupted-release tag is not the exact remote lightweight tag.");
  }
}

function verifyConfiguredAncestorRemoteState(
  manifestEntry,
  workflowCommit,
  latestPublishedTag,
  {
    expectedNewerTags = [manifestEntry.tag],
    verifyLatestRelease = true,
  } = {},
) {
  const remoteHead = parseRemoteRef(
    git("ls-remote", repositoryUrl, "refs/heads/main"),
    "refs/heads/main",
  );
  if (remoteHead !== workflowCommit) {
    fail("Remote main changed while the interrupted ancestor release was recovered.");
  }
  const publishedVersion = latestPublishedTag.slice(1);
  const newerTags = remoteStableTags()
    .filter((tag) => semver.gt(tag.slice(1), publishedVersion))
    .sort((first, second) => semver.compare(first.slice(1), second.slice(1)));
  const newerNotes = remoteSemanticReleaseNoteTags()
    .filter((tag) => semver.gt(tag.slice(1), publishedVersion))
    .sort((first, second) => semver.compare(first.slice(1), second.slice(1)));
  if (
    JSON.stringify(newerTags) !== JSON.stringify(expectedNewerTags)
    || JSON.stringify(newerNotes) !== JSON.stringify(expectedNewerTags)
  ) {
    fail("Interrupted ancestor release is no longer the sole newer tag and note.");
  }
  if (verifyLatestRelease) {
    const latestRelease = ghApi(
      `repositories/${TRUSTED_REPOSITORY_ID}/releases/latest`,
    );
    if (!latestRelease || latestRelease.tag_name !== latestPublishedTag) {
      fail("GitHub's latest Release changed during interrupted ancestor recovery.");
    }
  }
  verifyPinnedLightweightTag(manifestEntry);
  parseSemanticReleaseNote(manifestEntry.tag);
}

function verifyExactTaggedRemoteState(tag, commit, latestPublishedTag) {
  const remoteHead = parseRemoteRef(
    git("ls-remote", repositoryUrl, "refs/heads/main"),
    "refs/heads/main",
  );
  if (remoteHead !== commit) {
    fail("Remote main changed while the exact interrupted release was recovered.");
  }
  const publishedVersion = latestPublishedTag.slice(1);
  const newerTags = remoteStableTags()
    .filter((candidate) => semver.gt(candidate.slice(1), publishedVersion))
    .sort((first, second) => semver.compare(first.slice(1), second.slice(1)));
  const newerNotes = remoteSemanticReleaseNoteTags()
    .filter((candidate) => semver.gt(candidate.slice(1), publishedVersion))
    .sort((first, second) => semver.compare(first.slice(1), second.slice(1)));
  if (
    JSON.stringify(newerTags) !== JSON.stringify([tag])
    || JSON.stringify(newerNotes) !== JSON.stringify([tag])
  ) {
    fail("Exact interrupted release is no longer the sole newer tag and note.");
  }
  const latestRelease = ghApi(
    `repositories/${TRUSTED_REPOSITORY_ID}/releases/latest`,
  );
  if (!latestRelease || latestRelease.tag_name !== latestPublishedTag) {
    fail("GitHub's latest Release changed during exact interrupted-release recovery.");
  }
  if (resolveRemoteTag(tag) !== commit) {
    fail("Remote release tag changed during exact interrupted-release recovery.");
  }
  parseSemanticReleaseNote(tag);
}

function remoteSemanticReleaseNoteTags() {
  const output = git("ls-remote", repositoryUrl, "refs/notes/semantic-release-v*");
  if (output === "") {
    return [];
  }

  const tags = new Set();
  for (const row of output.split("\n")) {
    const [sha, ref, extra] = row.split(/\s+/);
    const match = /^refs\/notes\/semantic-release-(v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))$/
      .exec(ref);
    if (extra !== undefined || !/^[0-9a-f]{40}$/.test(sha)) {
      fail("A remote semantic-release note reference is malformed.");
    }
    if (match === null) {
      fail(`Remote semantic-release note reference is malformed: ${ref}.`);
    }
    if (tags.has(match[1])) {
      fail(`Multiple semantic-release note references use ${match[1]}.`);
    }
    tags.add(match[1]);
  }
  return [...tags];
}

function remoteStableTags() {
  const output = git("ls-remote", repositoryUrl, "refs/tags/v*");
  if (output === "") {
    return [];
  }
  const tags = new Set();
  for (const row of output.split("\n")) {
    const [sha, ref, extra] = row.split(/\s+/);
    const match = /^refs\/tags\/(.+?)(?:\^\{\})?$/.exec(ref);
    if (extra !== undefined || !/^[0-9a-f]{40}$/.test(sha) || match === null) {
      fail("A remote stable release tag reference is malformed.");
    }
    if (TAG_PATTERN.test(match[1])) {
      tags.add(match[1]);
    }
  }
  return [...tags];
}

function localTagExists(tag) {
  const result = spawnSync(
    "git",
    ["show-ref", "--verify", "--quiet", `refs/tags/${tag}`],
    { cwd: repositoryRoot, stdio: "ignore" },
  );
  if (result.error) {
    throw result.error;
  }
  if (result.status === 0) {
    return true;
  }
  if (result.status === 1) {
    return false;
  }
  fail(`Could not inspect local release tag ${tag}.`);
}

function parseSemanticReleaseNote(tag) {
  const noteRef = `refs/notes/semantic-release-${tag}`;
  const remoteNoteRef = git("ls-remote", repositoryUrl, noteRef);
  if (remoteNoteRef === "") {
    fail(`Remote semantic-release note is missing for ${tag}.`);
  }
  parseRemoteRef(remoteNoteRef, noteRef);
  git("fetch", "--force", "--no-tags", repositoryUrl, `+${noteRef}:${noteRef}`);
  const note = git("notes", "--ref", `semantic-release-${tag}`, "show", tag);
  let parsed;
  try {
    parsed = JSON.parse(note);
  } catch {
    fail(`Semantic-release note is invalid for ${tag}.`);
  }
  if (
    parsed === null
    || Array.isArray(parsed)
    || typeof parsed !== "object"
    || Object.keys(parsed).length !== 1
    || !Array.isArray(parsed.channels)
    || parsed.channels.length !== 1
    || parsed.channels[0] !== null
  ) {
    fail(`Semantic-release note is not the stable-channel marker for ${tag}.`);
  }
}

function expectedVersion(previousVersion, releaseType) {
  const next = semver.inc(previousVersion, releaseType);
  if (!next) {
    fail(`Cannot increment ${previousVersion} as a ${releaseType} release.`);
  }
  return next;
}

function sameAssetNames(release, version) {
  const actual = release.assets.map(({ name }) => name).sort();
  const expected = RELEASE_ASSET_NAMES(version).sort();
  return JSON.stringify(actual) === JSON.stringify(expected);
}

function isAlreadyPublishedRelease(release, tag, version, expectedCommit, body) {
  return release.tag_name === tag
    && release.name === `Tapziq Keyboard ${version}`
    && release.draft === false
    && release.prerelease === false
    && release.immutable === true
    && ["main", expectedCommit].includes(release.target_commitish)
    && release.body === body
    && release.assets.length === 4
    && sameAssetNames(release, version)
    && release.assets.every((asset) => {
      const expected = RELEASE_ASSETS(version).find(({ name }) => name === asset.name);
      return expected !== undefined
        && asset.label === expected.label
        && asset.content_type === expected.contentType
        && Number.isSafeInteger(asset.size)
        && asset.size > 0
        && typeof asset.digest === "string"
        && /^sha256:[0-9a-f]{64}$/.test(asset.digest);
    });
}

function matchingDraftMetadata(release, tag, version, expectedCommit, expectedBody) {
  return release.tag_name === tag
    && release.name === `Tapziq Keyboard ${version}`
    && release.draft === true
    && release.prerelease === false
    && release.immutable === false
    && ["main", expectedCommit].includes(release.target_commitish)
    && release.body === expectedBody;
}

function releaseBody(version, notes) {
  return `Download **\`Tapziq-v${version}.apk\`** to install this release on Android 8.0 or newer.\n\n`
    + `${notes}\n\n`
    + "### Release verification\n\n"
    + "The production-signed APK, license, notices, and `SHA256SUMS` were built and verified together from the tagged source commit. "
    + "The APK is signed by the permanent Tapziq release key.";
}

async function analyzeAndGenerate(previousTag, currentTag, currentCommit) {
  git("merge-base", "--is-ancestor", previousTag, currentCommit);
  const semanticReleaseRoot = path.dirname(require.resolve("semantic-release/package.json"));
  const { getCommits } = await import(pathToFileURL(
    path.join(semanticReleaseRoot, "lib", "git.js"),
  ));
  const commits = await getCommits(previousTag, currentCommit, {
    cwd: repositoryRoot,
    env: process.env,
  });
  if (commits.length === 0) {
    fail(`No commits exist between ${previousTag} and the orphan tag.`);
  }
  const { analyzeCommits } = await import("@semantic-release/commit-analyzer");
  const { generateNotes } = await import("@semantic-release/release-notes-generator");
  const logger = { log() {} };
  const releaseType = await analyzeCommits(CONVENTIONAL_COMMITS, {
    commits,
    cwd: repositoryRoot,
    logger,
  });
  if (releaseType === null) {
    fail(`The commits since ${previousTag} do not justify ${currentTag}.`);
  }
  const previousVersion = previousTag.slice(1);
  const version = currentTag.slice(1);
  if (expectedVersion(previousVersion, releaseType) !== version) {
    fail(`${currentTag} is not the expected ${releaseType} release after ${previousTag}.`);
  }
  const notes = await generateNotes(CONVENTIONAL_COMMITS, {
    commits,
    cwd: repositoryRoot,
    lastRelease: {
      gitHead: git("rev-list", "-n", "1", previousTag),
      gitTag: previousTag,
    },
    nextRelease: {
      gitHead: currentCommit,
      gitTag: currentTag,
      version,
    },
    options: { repositoryUrl },
  });
  return { notes, version };
}

function fileSha256(filePath) {
  return createHash("sha256").update(readFileSync(filePath)).digest("hex");
}

function inspectDraftAssets(assets, version, releaseDirectory) {
  if (!Array.isArray(assets) || assets.length > RELEASE_ASSETS(version).length) {
    fail("The draft contains an invalid release asset inventory.");
  }
  const matchingAssets = new Map();
  const staleAssets = [];
  const seenNames = new Set();
  const seenIds = new Set();
  for (const asset of assets) {
    const expected = RELEASE_ASSETS(version).find(({ name }) => name === asset.name);
    if (
      expected === undefined
      || seenNames.has(asset.name)
      || seenIds.has(asset.id)
      || asset.label !== expected.label
      || asset.content_type !== expected.contentType
      || asset.state !== "uploaded"
      || !Number.isSafeInteger(asset.id)
      || asset.id <= 0
      || !Number.isSafeInteger(asset.size)
      || asset.size <= 0
      || !/^sha256:[0-9a-f]{64}$/.test(asset.digest || "")
    ) {
      fail("The draft contains an unexpected or ambiguous release asset.");
    }
    const localPath = path.join(releaseDirectory, asset.name);
    const localContent = readFileSync(localPath);
    seenNames.add(asset.name);
    seenIds.add(asset.id);
    if (
      asset.size !== localContent.length
      || asset.digest !== `sha256:${fileSha256(localPath)}`
    ) {
      staleAssets.push(asset);
    } else {
      matchingAssets.set(asset.name, asset);
    }
  }
  return { matchingAssets, staleAssets };
}

function uploadAssets(draft, version) {
  const releaseDirectory = path.join(repositoryRoot, "dist", "release");
  if (!Number.isSafeInteger(draft.id) || draft.id <= 0) {
    fail("GitHub returned an invalid draft release ID.");
  }
  const expectedPath = `/repos/${repositoryFullName}/releases/${draft.id}/assets`;
  const uploadUrl = new URL(draft.upload_url.replace("{?name,label}", ""));
  if (
    uploadUrl.protocol !== "https:"
    || uploadUrl.hostname !== "uploads.github.com"
    || uploadUrl.pathname !== expectedPath
    || uploadUrl.search !== ""
  ) {
    fail("GitHub returned an unexpected release upload URL.");
  }
  let { matchingAssets, staleAssets } = inspectDraftAssets(
    draft.assets,
    version,
    releaseDirectory,
  );
  for (const asset of staleAssets) {
    ghApiWithoutResponse(
      `repositories/${TRUSTED_REPOSITORY_ID}/releases/assets/${asset.id}`,
      ["--method", "DELETE"],
    );
  }
  if (staleAssets.length > 0) {
    const afterDeletion = ghApi(
      `repositories/${TRUSTED_REPOSITORY_ID}/releases/${draft.id}`,
    );
    if (!matchingDraftMetadata(
      afterDeletion,
      currentTag,
      version,
      expectedCommit,
      expectedBody,
    )) {
      fail("Draft release changed unexpectedly while stale assets were removed.");
    }
    ({ matchingAssets, staleAssets } = inspectDraftAssets(
      afterDeletion.assets,
      version,
      releaseDirectory,
    ));
    if (staleAssets.length > 0) {
      fail("GitHub retained a stale draft asset after deletion.");
    }
  }
  for (const asset of RELEASE_ASSETS(version)) {
    if (matchingAssets.has(asset.name)) {
      continue;
    }
    const localPath = path.join(releaseDirectory, asset.name);
    ghApiWithFileInput(
      `${uploadUrl.href}?name=${encodeURIComponent(asset.name)}&label=${encodeURIComponent(asset.label)}`,
      [
        "--method", "POST",
        "-H", `Content-Type: ${asset.contentType}`,
      ],
      localPath,
    );
  }
  const refreshed = ghApi(`repositories/${TRUSTED_REPOSITORY_ID}/releases/${draft.id}`);
  if (
    !matchingDraftMetadata(
      refreshed,
      currentTag,
      version,
      expectedCommit,
      expectedBody,
    )
    || refreshed.assets.length !== RELEASE_ASSET_NAMES(version).length
    || !sameAssetNames(refreshed, version)
  ) {
    fail("Draft release changed unexpectedly while assets were uploaded.");
  }
  const finalAssets = inspectDraftAssets(
    refreshed.assets,
    version,
    releaseDirectory,
  );
  if (
    finalAssets.staleAssets.length !== 0
    || finalAssets.matchingAssets.size !== RELEASE_ASSET_NAMES(version).length
  ) {
    fail("The complete draft does not match the freshly verified release package.");
  }
}

const repositoryRoot = path.resolve(__dirname, "..");
let repositoryUrl;
let repositoryFullName;
let currentTag;
let expectedCommit;
let expectedBody;

function checkoutRemoteReleaseCommit(workflowCommit, remoteCommit) {
  const recoveryRef = "refs/remotes/tapziq-release/main";
  git(
    "fetch",
    "--force",
    "--no-tags",
    repositoryUrl,
    `+refs/heads/main:${recoveryRef}`,
  );
  if (git("rev-parse", "--verify", recoveryRef) !== remoteCommit) {
    fail("Fetched main does not match the advertised remote main commit.");
  }

  const commitRecord = git("rev-list", "--parents", "-n", "1", remoteCommit)
    .split(/\s+/);
  if (
    commitRecord.length !== 2
    || commitRecord[0] !== remoteCommit
    || commitRecord[1] !== workflowCommit
  ) {
    fail("Remote main advanced beyond GITHUB_SHA without one direct release commit.");
  }
  const subject = git("show", "-s", "--format=%s", remoteCommit);
  const subjectMatch = RELEASE_COMMIT_PATTERN.exec(subject);
  if (subjectMatch === null) {
    fail("Remote main advanced beyond GITHUB_SHA with a non-release commit.");
  }
  const changedPaths = git(
    "diff-tree",
    "--no-commit-id",
    "--name-only",
    "-r",
    workflowCommit,
    remoteCommit,
  ).split("\n").filter(Boolean);
  if (
    changedPaths.length !== 1
    || changedPaths[0] !== "app/build.gradle.kts"
  ) {
    fail("The generated release commit changed files outside source version metadata.");
  }
  try {
    verifySourceVersion(subjectMatch[1], {
      repositoryRoot,
      ref: remoteCommit,
    });
    const parentSource = execFileSync(
      "git",
      ["show", `${workflowCommit}:app/build.gradle.kts`],
      {
        cwd: repositoryRoot,
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
    const releaseSource = execFileSync(
      "git",
      ["show", `${remoteCommit}:app/build.gradle.kts`],
      {
        cwd: repositoryRoot,
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
    if (releaseSource !== sourceWithVersion(parentSource, subjectMatch[1])) {
      fail("The generated release commit changed non-version Gradle metadata.");
    }
  } catch (error) {
    fail(`The generated release commit has invalid source metadata: ${error.message}`);
  }

  git("checkout", "--detach", remoteCommit);
  if (git("status", "--porcelain", "--untracked-files=normal") !== "") {
    fail("Checking out the generated release commit produced a dirty worktree.");
  }
  process.stdout.write(
    `Recovered generated source-version commit ${remoteCommit}.\n`,
  );
}

async function reconcileTaggedRelease({
  commit,
  generatedParentCommit,
  latestPublished,
  mergedReleaseTags,
  packageEnvironment,
  postPackage,
  prePublish,
  tag,
  verifyPublishedRelease = (version, releaseCommit) => {
    run(path.join(repositoryRoot, PUBLISHED_VERIFIER_PATH), [
      version,
      releaseCommit,
    ], { stdio: "inherit" });
  },
}) {
  currentTag = tag;
  expectedCommit = commit;
  const version = currentTag.slice(1);
  const remoteTag = resolveRemoteTag(currentTag);
  if (git("rev-list", "-n", "1", currentTag) !== expectedCommit || remoteTag !== expectedCommit) {
    fail("The local and remote release tags do not both resolve to the release source commit.");
  }
  parseSemanticReleaseNote(currentTag);

  const currentOrNewerTags = mergedReleaseTags.filter(
    (candidate) => !semver.lt(candidate.slice(1), version),
  );
  if (currentOrNewerTags.length !== 1 || currentOrNewerTags[0] !== currentTag) {
    fail("Additional semantic-version tags make release provenance ambiguous.");
  }
  const previousTags = mergedReleaseTags
    .filter((candidate) => semver.lt(candidate.slice(1), version))
    .sort((first, second) => semver.rcompare(first.slice(1), second.slice(1)));
  if (previousTags.length === 0) {
    fail(`Could not resolve the previous stable release before ${currentTag}.`);
  }
  const [previousTag] = previousTags;
  const previousRemoteTag = resolveRemoteTag(previousTag);
  if (git("rev-list", "-n", "1", previousTag) !== previousRemoteTag) {
    fail("The previous release tag has inconsistent local and remote provenance.");
  }
  if (generatedParentCommit) {
    try {
      verifySourceVersion(previousTag.slice(1), {
        repositoryRoot,
        ref: generatedParentCommit,
      });
    } catch (error) {
      fail(`Generated release parent has invalid source metadata: ${error.message}`);
    }
  }
  const result = await analyzeAndGenerate(previousTag, currentTag, expectedCommit);
  expectedBody = releaseBody(result.version, result.notes);

  const existingRelease = releaseApiOrNull(
    `repositories/${TRUSTED_REPOSITORY_ID}/releases/tags/${currentTag}`,
  );
  if (existingRelease && existingRelease.draft === false) {
    if (latestPublished.tag_name !== currentTag) {
      fail(`Published release ${currentTag} is not GitHub's latest stable release.`);
    }
    if (!isAlreadyPublishedRelease(
      existingRelease,
      currentTag,
      version,
      expectedCommit,
      expectedBody,
    )) {
      fail(`Published release ${currentTag} does not satisfy the release contract.`);
    }
    verifyPublishedRelease(version, expectedCommit);
    return;
  }

  if (latestPublished.tag_name !== previousTag) {
    fail("The previous semantic-version tag is not GitHub's latest stable release.");
  }

  const drafts = ghApi(
    `repositories/${TRUSTED_REPOSITORY_ID}/releases?per_page=100`,
  ).filter((release) => release.tag_name === currentTag);
  if (drafts.length > 1) {
    fail(`Multiple GitHub releases use tag ${currentTag}.`);
  }
  if (existingRelease && !drafts.some(({ id }) => id === existingRelease.id)) {
    fail(`The API returned inconsistent release state for ${currentTag}.`);
  }
  let draft = drafts[0];
  if (draft && !matchingDraftMetadata(
    draft,
    currentTag,
    result.version,
    expectedCommit,
    expectedBody,
  )) {
    fail(`Existing draft ${currentTag} does not exactly match the expected release.`);
  }

  run(path.join(repositoryRoot, "scripts", "package-semantic-release.sh"), [
    result.version,
    expectedCommit,
    "--allow-existing-tag",
  ], {
    env: packageEnvironment,
    stdio: "inherit",
  });
  if (postPackage) {
    await postPackage(result);
  }

  if (!draft) {
    // The exact remote tag is already verified above. Do not pass its commit as
    // target_commitish: Actions' GITHUB_TOKEN cannot target historical commits
    // whose workflow files differ from the default branch.
    draft = ghApi(`repositories/${TRUSTED_REPOSITORY_ID}/releases`, [
      "--method", "POST",
      "-f", `tag_name=${currentTag}`,
      "-f", `name=Tapziq Keyboard ${result.version}`,
      "-f", `body=${expectedBody}`,
      "-F", "draft=true",
      "-F", "prerelease=false",
      "-f", "make_latest=true",
    ]);
    if (!matchingDraftMetadata(
      draft,
      currentTag,
      result.version,
      expectedCommit,
      expectedBody,
    ) || draft.assets.length !== 0) {
      fail("GitHub did not create the exact expected draft release.");
    }
  }

  uploadAssets(draft, result.version);
  if (prePublish) {
    await prePublish();
  }
  const published = ghApi(`repositories/${TRUSTED_REPOSITORY_ID}/releases/${draft.id}`, [
    "--method", "PATCH",
    "-F", "draft=false",
    "-f", "make_latest=true",
  ]);
  if (published.draft !== false || published.tag_name !== currentTag) {
    fail(`GitHub did not publish ${currentTag}.`);
  }
  verifyPublishedRelease(result.version, expectedCommit);
}

async function reconcileConfiguredAncestor({
  latestPublished,
  manifestEntry,
  mergedReleaseTags,
  workflowCommit,
}) {
  const expectedNewerTags = latestPublished.tag_name === manifestEntry.tag
    ? []
    : [manifestEntry.tag];
  verifyConfiguredAncestorRemoteState(
    manifestEntry,
    workflowCommit,
    latestPublished.tag_name,
    { expectedNewerTags },
  );
  if (!isOnFirstParentChain(manifestEntry.commit, workflowCommit)) {
    fail("Configured interrupted release is not on GITHUB_SHA's first-parent chain.");
  }
  const parentCommit = git("rev-parse", `${manifestEntry.commit}^`);
  const version = validateGeneratedReleaseCommit(
    parentCommit,
    manifestEntry.commit,
    manifestEntry.tag,
  );
  verifySourceVersion(version, { repositoryRoot, ref: workflowCommit });
  const recoveryVerifier = approvedRecoveryVerifier(
    workflowCommit,
    manifestEntry.commit,
  );

  const frozenSmoke = freezeWorkflowSmokeScript(workflowCommit);
  let reconciliationError;
  try {
    git("checkout", "--detach", manifestEntry.commit);
    if (git("status", "--porcelain", "--untracked-files=normal") !== "") {
      fail("Checking out the configured interrupted release produced a dirty worktree.");
    }
    await reconcileTaggedRelease({
      commit: manifestEntry.commit,
      generatedParentCommit: parentCommit,
      latestPublished,
      mergedReleaseTags,
      packageEnvironment: childEnvironment(
        { TAPZIQ_RUN_EMULATOR_SMOKE: "0" },
        ["GH_TOKEN", "GITHUB_TOKEN"],
      ),
      postPackage(result) {
        const apkPath = path.join(
          repositoryRoot,
          "dist",
          "release",
          `Tapziq-v${result.version}.apk`,
        );
        run(frozenSmoke.scriptPath, [
          apkPath,
          result.version,
          String(sourceVersionCode(result.version)),
        ], {
          env: childEnvironment(
            {
              TAPZIQ_PROOFREAD_ROWS_BEFORE_LETTERS: String(
                manifestEntry.proofreadRowsBeforeLetters,
              ),
            },
            [
              "GH_TOKEN",
              "GITHUB_TOKEN",
              "TAPZIQ_RELEASE_KEY_ALIAS",
              "TAPZIQ_RELEASE_KEY_PASSWORD",
              "TAPZIQ_RELEASE_REPOSITORY",
              "TAPZIQ_RELEASE_STORE_BASE64",
              "TAPZIQ_RELEASE_STORE_FILE",
              "TAPZIQ_RELEASE_STORE_PASSWORD",
            ],
          ),
          stdio: "inherit",
        });
        verifyConfiguredAncestorRemoteState(
          manifestEntry,
          workflowCommit,
          latestPublished.tag_name,
          { expectedNewerTags },
        );
      },
      prePublish() {
        verifyConfiguredAncestorRemoteState(
          manifestEntry,
          workflowCommit,
          latestPublished.tag_name,
          { expectedNewerTags },
        );
      },
      tag: manifestEntry.tag,
      verifyPublishedRelease(releaseVersion, releaseCommit) {
        runRecoveryVerifier(recoveryVerifier, releaseVersion, releaseCommit);
      },
    });
  } catch (error) {
    reconciliationError = error;
  }

  try {
    git("checkout", "--detach", workflowCommit);
    if (git("rev-parse", "HEAD") !== workflowCommit) {
      fail("Could not restore GITHUB_SHA after interrupted-release recovery.");
    }
    if (git("status", "--porcelain", "--untracked-files=normal") !== "") {
      fail("Restoring GITHUB_SHA after interrupted-release recovery produced a dirty worktree.");
    }
    verifyConfiguredAncestorRemoteState(
      manifestEntry,
      workflowCommit,
      latestPublished.tag_name,
      { expectedNewerTags, verifyLatestRelease: false },
    );
  } catch (restoreError) {
    if (reconciliationError) {
      reconciliationError.message += ` Recovery checkout restoration failed: ${restoreError.message}`;
    } else {
      reconciliationError = restoreError;
    }
  } finally {
    rmSync(frozenSmoke.directory, { recursive: true, force: true });
  }
  if (reconciliationError) {
    throw reconciliationError;
  }
}

async function main() {
  if (process.env.GITHUB_REF !== "refs/heads/main") {
    fail("Interrupted release reconciliation is restricted to main.");
  }
  if (process.env.GITHUB_REPOSITORY_ID !== TRUSTED_REPOSITORY_ID) {
    fail("Interrupted release reconciliation is restricted to the trusted Tapziq repository.");
  }
  const workflowCommit = (process.env.GITHUB_SHA || "").toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(workflowCommit)) {
    fail("GITHUB_SHA must be a full Git commit SHA.");
  }
  if (git("rev-parse", "HEAD") !== workflowCommit) {
    fail("The checkout does not match GITHUB_SHA.");
  }
  if (git("status", "--porcelain", "--untracked-files=normal") !== "") {
    fail("Interrupted release reconciliation requires a clean worktree.");
  }

  const repository = ghApi(`repositories/${TRUSTED_REPOSITORY_ID}`);
  if (
    String(repository.id) !== TRUSTED_REPOSITORY_ID
    || repository.archived
    || repository.disabled
    || repository.default_branch !== "main"
  ) {
    fail("The trusted Tapziq repository is not an active main-branch repository.");
  }
  const repositoryComponents = typeof repository.full_name === "string"
    ? repository.full_name.split("/")
    : [];
  if (
    repositoryComponents.length !== 2
    || repositoryComponents.some((component) => (
      !/^[A-Za-z0-9_.-]+$/.test(component)
      || component === "."
      || component === ".."
    ))
  ) {
    fail("The trusted Tapziq repository name is invalid.");
  }
  if (process.env.GITHUB_REPOSITORY !== repository.full_name) {
    fail("GITHUB_REPOSITORY does not resolve to the trusted Tapziq repository.");
  }
  repositoryFullName = repository.full_name;
  repositoryUrl = `https://github.com/${repositoryFullName}.git`;
  const remoteHead = parseRemoteRef(
    git("ls-remote", repositoryUrl, "refs/heads/main"),
    "refs/heads/main",
  );
  expectedCommit = workflowCommit;
  if (remoteHead !== workflowCommit) {
    checkoutRemoteReleaseCommit(workflowCommit, remoteHead);
    expectedCommit = remoteHead;
  }

  const latestPublished = ghApi(
    `repositories/${TRUSTED_REPOSITORY_ID}/releases/latest`,
  );
  if (!latestPublished || !TAG_PATTERN.test(latestPublished.tag_name)) {
    fail("Could not resolve GitHub's latest stable Tapziq release.");
  }
  if (latestPublished.tag_name !== "v0.1.0" && latestPublished.immutable !== true) {
    fail("GitHub's latest automated Tapziq release is not immutable.");
  }

  const mergedReleaseTags = git("tag", "--merged", expectedCommit)
    .split("\n")
    .filter((tag) => TAG_PATTERN.test(tag));
  const latestPublishedVersion = latestPublished.tag_name.slice(1);
  const recoveryTags = new Set(remoteStableTags().filter(
    (tag) => semver.gt(tag.slice(1), latestPublishedVersion),
  ));
  for (const mergedTag of mergedReleaseTags.filter(
    (tag) => semver.gt(tag.slice(1), latestPublishedVersion),
  )) {
    recoveryTags.add(mergedTag);
  }
  for (const noteTag of remoteSemanticReleaseNoteTags()) {
    if (!semver.gt(noteTag.slice(1), latestPublishedVersion)) {
      continue;
    }
    if (!localTagExists(noteTag)) {
      fail(
        `Semantic-release note ${noteTag} has no matching local tag; `
          + "release recovery state is ambiguous.",
      );
    }
    recoveryTags.add(noteTag);
  }

  const releaseTags = git("tag", "--points-at", expectedCommit)
    .split("\n")
    .filter((tag) => TAG_PATTERN.test(tag));
  const orderedRecoveryTags = [...recoveryTags]
    .sort((first, second) => semver.compare(first.slice(1), second.slice(1)));
  const recoveryIsExactlyCurrent = orderedRecoveryTags.length === 1
    && releaseTags.length === 1
    && orderedRecoveryTags[0] === releaseTags[0];
  if (orderedRecoveryTags.length > 0 && !recoveryIsExactlyCurrent) {
    if (
      orderedRecoveryTags.length !== 1
      || releaseTags.length !== 0
      || expectedCommit !== workflowCommit
      || remoteHead !== workflowCommit
    ) {
      fail(
        "A newer reachable semantic-release tag or note lacks a matching latest "
          + "immutable GitHub Release and is not an unambiguous configured "
          + `ancestor of GITHUB_SHA: ${orderedRecoveryTags.join(", ")}`,
      );
    }
    const [ancestorTag] = orderedRecoveryTags;
    const manifestEntry = loadRecoveryManifest(workflowCommit).get(ancestorTag);
    if (!manifestEntry) {
      fail(
        `Interrupted ancestor release ${ancestorTag} is not pinned in `
          + `${RECOVERY_MANIFEST_PATH}.`,
      );
    }
    if (git("rev-list", "-n", "1", ancestorTag) !== manifestEntry.commit) {
      fail("Configured interrupted-release commit does not match its local tag.");
    }
    await reconcileConfiguredAncestor({
      latestPublished,
      manifestEntry,
      mergedReleaseTags,
      workflowCommit,
    });
    setHandled(false);
    return;
  }

  if (
    orderedRecoveryTags.length === 0
    && releaseTags.length === 0
    && expectedCommit === workflowCommit
    && remoteHead === workflowCommit
  ) {
    const publishedAncestor = loadRecoveryManifest(workflowCommit)
      .get(latestPublished.tag_name);
    if (publishedAncestor) {
      if (git("rev-list", "-n", "1", publishedAncestor.tag) !== publishedAncestor.commit) {
        fail("Published recovery checkpoint does not match its configured local tag.");
      }
      await reconcileConfiguredAncestor({
        latestPublished,
        manifestEntry: publishedAncestor,
        mergedReleaseTags,
        workflowCommit,
      });
      setHandled(false);
      return;
    }
  }

  if (releaseTags.length === 0) {
    setHandled(false);
    return;
  }
  if (releaseTags.length !== 1) {
    fail("Expected at most one semantic-version tag on the release source commit.");
  }
  await reconcileTaggedRelease({
    commit: expectedCommit,
    latestPublished,
    mergedReleaseTags,
    prePublish() {
      verifyExactTaggedRemoteState(
        releaseTags[0],
        expectedCommit,
        latestPublished.tag_name,
      );
    },
    tag: releaseTags[0],
  });
  setHandled(true);
}

main().catch((error) => {
  process.stderr.write(`ERROR: ${error.message}\n`);
  process.exitCode = 1;
});
